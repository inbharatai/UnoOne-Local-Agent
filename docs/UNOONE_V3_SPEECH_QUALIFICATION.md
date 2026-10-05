# V3 speech qualification: BLOCKED / NOT MEASURED

Real consented human audio has not been collected. The audio manifest and predictions are deliberately EMPTY. Pending transcript prompts are a collection plan, not recordings, inference output, or accuracy evidence. Synthetic unit tests establish scorer arithmetic only. No WER/CER, identity, wake, or interruption quality claim is currently justified.

## Run locally (Python 3.9+; stdlib only)

From the repository root in Windows PowerShell or cmd, paste:

```bat
python -m unittest discover -s evaluation\speech -p "test_*.py" -v
python evaluation\speech\score.py --references evaluation\speech\references.jsonl --predictions evaluation\speech\predictions.jsonl --manifest evaluation\speech\audio-manifest.jsonl --audio-root evaluation\speech
```

Linux uses `python3` and forward slashes. Exit 2 means invalid/incomplete evidence; exit 0 means evidence complete, NOT acceptance or product qualification. The supplied empty corpus produces `PENDING_OR_MISSING`, zero ready cases and null rates. `EXACT_MATCH` is a case comparison label, never an accuracy gate. Even complete evidence remains `NOT_QUALIFIED` until an approved acceptance policy exists; no unmeasured numeric threshold is hardcoded.

## JSONL contracts

UTF-8, one object per line, unique nonempty `id`; blank lines allowed. Unknown prediction IDs and duplicate IDs are errors.

Reference example (proposed labels must be human-adjudicated before ready):

```json
{"id":"human-001","status":"ready","transcript":"पंकज को कॉल करो।","intent":"call","slots":{"recipient_name":"पंकज"},"recipient_id":"corpus:pankaj","wake_expected":false,"stop_expected":false}
```

`status`: `ready`, `pending` (not yet collected/annotated), or `skipped` (record exclusion reason). Ready rows require every field shown. `recipient_id` is a stable identity in a consented test address book, NOT name text, phone number, or contact ordering. Null means no recipient/action expected. Intent and slot labels are evaluation annotations, not a promise of existing Android wire-format compatibility.

Prediction example:

```json
{"id":"human-001","status":"ok","transcript":"पंकज को कॉल करो।","intent":"call","slots":{"recipient_name":"पंकज"},"recipient_id":"corpus:pankaj","wake_detected":false,"stop_latency_ms":null}
```

Missing rows, non-`ok` predictions (including skipped), missing fields, missing wake results and missing required stop times are failures, not exclusions. Missing predictions retain reference denominators and use an empty transcript (all reference tokens deleted). Silence has zero reference tokens: insertions are still errors; undefined zero-denominator rates are null, never zero. Pending/skipped *references* are counted separately, excluded from metric denominators, and block complete evidence.

Manifest row template (NOT an actual recording; do not copy as evidence):

```json
{"id":"human-001","audio":"audio/human-001.wav","sha256":"REPLACE_WITH_64_LOWERCASE_HEX_DIGITS","source":"human","consent":true,"speaker_id":"speaker-001","split":"dev"}
```

Use SHA256 of exact audio file bytes. CLI validates ready-reference coverage, relative paths contained under audio root, file existence, digest equality, human/consent declarations, and no same speaker across dev/test. Hash checks establish file integrity only: they cannot prove a recording is human, consent is authentic, or that a prediction came from that audio. Independently retain consent records and signed run metadata (model/version, decoder configuration, device, timestamps, exporter version). Synthetic hash-test bytes are explicitly not audio evidence.

## Metric definitions

* Normalize NFC and collapse whitespace only. Preserve Devanagari vowel signs, nukta, virama and nasal marks; preserve case and punctuation. WER uses whitespace-separated words. CER uses Unicode code points, including normalized spaces, not grapheme clusters. Micro-average summed edit distances / summed reference lengths. Rates may exceed 1 due to insertions.
* Intent, full slot dictionary and recipient ID use exact comparison, independently and jointly, divided by all ready cases. Correct spelling is not proof of correct identity. No alias/fuzzy recipient matching.
* Wake false-alarm rate = detected wake on negative trials / all negative trials. Wake miss rate includes missing results on positive trials. Missing negative results are separately counted and included in `wake_negative_failure_rate`, so an absent output cannot look like a successful rejection. These are per-trial rates, NOT false alarms/hour.
* Stop latency summary reports count, mean, nearest-rank p50/p95 and max in milliseconds. Missing/nonfinite/negative times count as missing failures; never convert absent timing to zero. Define measurement start as human stop-utterance onset and end as verified action/audio cessation using the same monotonic clock; record both raw timestamps in future exports. The scorer summarizes supplied latency, not inferred ASR timing.

## Future consented human corpus

`evaluation/speech/references.jsonl` contains exact English/Hindi/Hinglish prompts covering Pankaj vs Madhav, negation redirection, approval/denial, background-noise silence, near-wake negatives, and interruption. All remain pending. Confirmation tests require a logged prior pending action and expected identity; denial must issue no action. Run interruptions during TTS, inference and queued/executing actions; record cessation outcome separately from transcript correctness. Noise trials should include consented conversational noise, TV/radio-like playback, fan/traffic and silence; record duration and SNR/distance when measurable. Do not treat a spoken wake prompt as approved deployed wake vocabulary until checked against the build.

Recruit consented speakers across English, Hindi, code-switching accents and speaking styles. Assign each speaker exclusively to dev or held-out test BEFORE tuning; keep all that person's sessions in that split. Balance both recipient identities and add same-name distinct-identity ambiguity trials. Do not tune on test recordings. Store anonymized speaker IDs, collection consent, language, session, noise condition and action context. Human-review transcript/intent/slots/identity and disagreement adjudication. Report per-language/condition and split-specific results with speaker/sample counts and uncertainty before proposing an acceptance policy. Use separate input files per split; scorer does not automatically stratify.

Android audio/prediction/timestamp export integration is currently unavailable in this harness. No automated device capture, Android benchmark run, end-to-end inference, production changes, or Gradle validation is claimed. Future exporter must emit the contracts above and immutable audio/run provenance; until implemented and recordings collected, this remains an executable measurement harness, not speech qualification.
