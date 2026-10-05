#!/usr/bin/env python3
"""Local evidence scorer; stdlib only. No accuracy qualification without real audio."""
import argparse
import hashlib
import json
import math
import sys
import unicodedata
from pathlib import Path


def normalize(text):
    if not isinstance(text, str):
        raise ValueError('transcript must be a string')
    # Keep all Hindi combining marks, punctuation and case. Collapse whitespace only.
    return ' '.join(unicodedata.normalize('NFC', text).split())


def distance(a, b):
    row = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        nxt = [i]
        for j, y in enumerate(b, 1):
            nxt.append(min(nxt[-1] + 1, row[j] + 1, row[j - 1] + (x != y)))
        row = nxt
    return row[-1]


def read_jsonl(path):
    rows = []
    for line_number, line in enumerate(Path(path).read_text(encoding='utf-8').splitlines(), 1):
        if line.strip():
            try:
                row = json.loads(line)
                if not isinstance(row, dict):
                    raise ValueError('expected object')
                rows.append(row)
            except (ValueError, TypeError) as exc:
                raise ValueError(f'{path}:{line_number}: {exc}') from exc
    return rows


def indexed(rows):
    result = {}
    for row in rows:
        key = row.get('id')
        if not isinstance(key, str) or not key or key in result:
            raise ValueError('each row needs a unique nonempty string id')
        result[key] = row
    return result


def validate_manifest(manifest, refs, root):
    entries = indexed(manifest)
    errors = []
    splits = {}
    for ref in refs:
        if ref.get('status') != 'ready':
            continue
        entry = entries.get(ref['id'])
        if entry is None:
            errors.append(ref['id'] + ': missing audio manifest entry')
            continue
        try:
            if entry.get('consent') is not True or entry.get('source') != 'human':
                raise ValueError('consented human audio required')
            speaker = entry.get('speaker_id')
            split = entry.get('split')
            if not isinstance(speaker, str) or not speaker or split not in ('dev', 'test'):
                raise ValueError('speaker_id and dev/test split required')
            if speaker in splits and splits[speaker] != split:
                raise ValueError('speaker leaks across dev/test')
            splits[speaker] = split
            if not isinstance(entry.get('audio'), str) or not entry['audio']:
                raise ValueError('audio relative path required')
            path = (root / entry['audio']).resolve()
            if not path.is_relative_to(root.resolve()) or Path(entry['audio']).is_absolute():
                raise ValueError('audio must stay under audio root')
            digest = entry.get('sha256', '')
            if len(digest) != 64 or any(c not in '0123456789abcdef' for c in digest):
                raise ValueError('lowercase SHA256 required')
            if hashlib.sha256(path.read_bytes()).hexdigest() != digest:
                raise ValueError('SHA256 mismatch')
        except (OSError, ValueError, TypeError) as exc:
            errors.append(ref['id'] + ': ' + str(exc))
    return errors


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def score(refs, predictions):
    references, predictions = indexed(refs), indexed(predictions)
    extra = sorted(set(predictions) - set(references))
    if extra:
        raise ValueError('unknown prediction ids: ' + ', '.join(extra))
    counts = dict(total=len(refs), ready=0, pending=0, skipped=0, missing_predictions=0,
                  failed_cases=0, reference_words=0, word_edits=0, reference_characters=0,
                  character_edits=0, exact_intent=0, exact_slots=0, exact_recipient=0,
                  exact_semantics=0, wake_positive=0, wake_negative=0,
                  wake_false_alarms=0, wake_misses=0, wake_missing_positive=0,
                  wake_missing_negative=0, stop_expected=0, stop_missing=0)
    latencies = []
    for ref in refs:
        status = ref.get('status')
        if status in ('pending', 'skipped'):
            counts[status] += 1
            continue
        if status != 'ready':
            raise ValueError('reference status must be ready, pending or skipped')
        for field in ('transcript', 'intent', 'slots', 'recipient_id', 'wake_expected', 'stop_expected'):
            if field not in ref:
                raise ValueError(ref['id'] + ': missing reference ' + field)
        if not isinstance(ref['intent'], str) or not isinstance(ref['slots'], dict):
            raise ValueError('intent string and slots object required')
        if ref['recipient_id'] is not None and not isinstance(ref['recipient_id'], str):
            raise ValueError('recipient_id must be string or null')
        if any(type(ref[f]) is not bool for f in ('wake_expected', 'stop_expected')):
            raise ValueError('wake_expected and stop_expected must be booleans')
        counts['ready'] += 1
        pred = predictions.get(ref['id'])
        missing = pred is None or pred.get('status') != 'ok'
        if missing:
            counts['missing_predictions'] += 1
            pred = {}
        reference = normalize(ref['transcript'])
        hypothesis = normalize(pred.get('transcript', ''))
        word_edits = distance(reference.split(), hypothesis.split())
        # CER counts Unicode code points including spaces after normalization; not graphemes.
        char_edits = distance(reference, hypothesis)
        counts['reference_words'] += len(reference.split())
        counts['reference_characters'] += len(reference)
        counts['word_edits'] += word_edits
        counts['character_edits'] += char_edits
        exact = []
        for field, counter in [('intent', 'exact_intent'), ('slots', 'exact_slots'),
                               ('recipient_id', 'exact_recipient')]:
            match = not missing and field in pred and pred[field] == ref[field]
            counts[counter] += int(match)
            exact.append(match)
        counts['exact_semantics'] += int(all(exact))
        wake = pred.get('wake_detected')
        positive = ref['wake_expected']
        counts['wake_positive' if positive else 'wake_negative'] += 1
        if type(wake) is not bool:
            counts['wake_missing_positive' if positive else 'wake_missing_negative'] += 1
        elif positive and not wake:
            counts['wake_misses'] += 1
        elif not positive and wake:
            counts['wake_false_alarms'] += 1
        latency_missing = False
        if ref['stop_expected']:
            counts['stop_expected'] += 1
            latency = pred.get('stop_latency_ms')
            if type(latency) not in (int, float) or not math.isfinite(latency) or latency < 0:
                counts['stop_missing'] += 1
                latency_missing = True
            else:
                latencies.append(latency)
        failed = missing or 'transcript' not in pred or word_edits != 0 or not all(exact)
        failed = failed or type(wake) is not bool or wake != positive or latency_missing
        counts['failed_cases'] += int(failed)
    n = counts['ready']
    sorted_latencies = sorted(latencies)
    return dict(counts=counts, wer=ratio(counts['word_edits'], counts['reference_words']),
                cer=ratio(counts['character_edits'], counts['reference_characters']),
                exact_intent_rate=ratio(counts['exact_intent'], n),
                exact_slot_rate=ratio(counts['exact_slots'], n),
                exact_recipient_rate=ratio(counts['exact_recipient'], n),
                exact_semantic_rate=ratio(counts['exact_semantics'], n),
                wake_false_alarm_rate=ratio(counts['wake_false_alarms'], counts['wake_negative']),
                wake_miss_rate=ratio(counts['wake_misses'] + counts['wake_missing_positive'], counts['wake_positive']),
                wake_negative_failure_rate=ratio(counts['wake_false_alarms'] + counts['wake_missing_negative'], counts['wake_negative']),
                stop_latency_ms=dict(count=len(latencies), mean=ratio(sum(latencies), len(latencies)),
                    p50=sorted_latencies[math.ceil(len(latencies)*.5)-1] if latencies else None,
                    p95=sorted_latencies[math.ceil(len(latencies)*.95)-1] if latencies else None,
                    maximum=max(latencies) if latencies else None))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--references', required=True)
    parser.add_argument('--predictions', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--audio-root', required=True)
    args = parser.parse_args()
    try:
        refs = read_jsonl(args.references)
        report = score(refs, read_jsonl(args.predictions))
        errors = validate_manifest(read_jsonl(args.manifest), refs, Path(args.audio_root))
        report['audio_validation_errors'] = errors
        c = report['counts']
        complete = c['ready'] > 0 and not any(c[k] for k in ('pending', 'skipped', 'missing_predictions')) and not errors
        report['evidence_status'] = 'COMPLETE' if complete else 'PENDING_OR_MISSING'
        report['qualification'] = 'NOT_QUALIFIED: no approved measured acceptance policy supplied'
        report['case_status'] = 'FAIL' if c['failed_cases'] else ('EXACT_MATCH' if c['ready'] else 'PENDING')
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0 if complete else 2
    except (OSError, ValueError, TypeError) as exc:
        print(json.dumps({'evidence_status': 'INVALID', 'error': str(exc)}, ensure_ascii=False))
        return 2


if __name__ == '__main__':
    sys.exit(main())
