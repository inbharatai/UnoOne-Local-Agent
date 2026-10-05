"""SYNTHETIC software fixtures only: never speech-accuracy evidence."""
import hashlib
import tempfile
import unittest
from pathlib import Path
from score import distance, normalize, score, validate_manifest, indexed


def reference(**overrides):
    r = dict(id='synthetic', status='ready', transcript='call Pankaj', intent='call',
             slots={'recipient_name': 'Pankaj'}, recipient_id='fixture:pankaj',
             wake_expected=True, stop_expected=False)
    r.update(overrides)
    return r


def prediction(**overrides):
    p = dict(id='synthetic', status='ok', transcript='call Pankaj', intent='call',
             slots={'recipient_name': 'Pankaj'}, recipient_id='fixture:pankaj', wake_detected=True)
    p.update(overrides)
    return p


class SyntheticScorerTests(unittest.TestCase):
    def test_known_edits(self):
        self.assertEqual(distance('kitten', 'sitting'), 3)
        self.assertEqual(distance('one two'.split(), 'one three four'.split()), 2)
        self.assertEqual(distance('', 'abc'), 3)

    def test_devanagari_marks_preserved(self):
        self.assertEqual(normalize('  हिंदी  '), 'हिंदी')
        self.assertEqual(distance(normalize('कि'), normalize('क')), 1)
        self.assertEqual(distance(normalize('हिंदी'), normalize('हिदी')), 1)
        self.assertEqual(normalize('\u0958'), normalize('\u0915\u093c'))
        self.assertNotEqual(normalize('नहीं'), normalize('नही'))

    def test_missing_is_failure_and_deletions(self):
        r = score([reference()], [])
        self.assertEqual(r['wer'], 1)
        self.assertEqual(r['cer'], 1)
        self.assertEqual(r['exact_recipient_rate'], 0)
        self.assertEqual(r['counts']['failed_cases'], 1)
        self.assertEqual(r['wake_miss_rate'], 1)

    def test_pending_skipped_excluded_not_pass(self):
        r = score([reference(), reference(id='p', status='pending'), reference(id='s', status='skipped')], [prediction()])
        self.assertEqual(r['counts']['ready'], 1)
        self.assertEqual(r['counts']['pending'], 1)
        self.assertEqual(r['counts']['skipped'], 1)
        self.assertEqual(r['counts']['reference_words'], 2)
        self.assertEqual(r['exact_intent_rate'], 1)
        self.assertIsNone(score([], [])['wer'])

    def test_micro_denominator_and_insertions(self):
        r = score([reference(), reference(id='other', transcript='one two three')], [prediction()])
        self.assertEqual(r['wer'], 3/5)
        self.assertEqual(r['exact_intent_rate'], .5)
        r = score([reference(transcript='')], [prediction(transcript='noise')])
        self.assertEqual(r['counts']['word_edits'], 1)
        self.assertIsNone(r['wer'])
        self.assertEqual(r['counts']['failed_cases'], 1)

    def test_identity_not_name(self):
        r = score([reference()], [prediction(recipient_id='fixture:madhav')])
        self.assertEqual(r['wer'], 0)
        self.assertEqual(r['exact_recipient_rate'], 0)
        self.assertEqual(r['exact_semantic_rate'], 0)

    def test_wake_and_stop(self):
        r = score([reference(wake_expected=False, stop_expected=True)], [prediction(stop_latency_ms=123)])
        self.assertEqual(r['wake_false_alarm_rate'], 1)
        self.assertEqual(r['stop_latency_ms']['p95'], 123)
        r = score([reference(wake_expected=False, stop_expected=True)], [])
        self.assertEqual(r['wake_negative_failure_rate'], 1)
        self.assertEqual(r['counts']['stop_missing'], 1)
        self.assertIsNone(r['stop_latency_ms']['mean'])

    def test_prediction_skip_not_exclusion(self):
        r = score([reference()], [prediction(status='skipped')])
        self.assertEqual(r['counts']['missing_predictions'], 1)
        self.assertEqual(r['wer'], 1)

    def test_duplicates_unknown(self):
        with self.assertRaises(ValueError):
            indexed([reference(), reference()])
        with self.assertRaises(ValueError):
            score([reference()], [prediction(id='other')])

    def test_manifest_hash_consent_missing_and_split(self):
        # Deliberately synthetic bytes, testing hash logic, NOT recorded speech.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / 'fixture.bin').write_bytes(b'synthetic-not-audio')
            entry = dict(id='synthetic', audio='fixture.bin', sha256=hashlib.sha256(b'synthetic-not-audio').hexdigest(),
                         consent=True, source='human', speaker_id='fixture-speaker', split='dev')
            self.assertEqual(validate_manifest([entry], [reference()], root), [])
            self.assertTrue(validate_manifest([], [reference()], root))
            self.assertTrue(validate_manifest([dict(entry, sha256='0'*64)], [reference()], root))
            self.assertTrue(validate_manifest([dict(entry, consent=False)], [reference()], root))
            self.assertTrue(validate_manifest([dict(entry, audio='../outside')], [reference()], root))
            self.assertTrue(validate_manifest([entry, dict(entry, id='other', split='test')], [reference(), reference(id='other')], root))


if __name__ == '__main__':
    unittest.main()
