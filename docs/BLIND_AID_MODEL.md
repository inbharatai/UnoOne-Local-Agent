# Blind Aid object detector

UnoOne bundles Google's EfficientDet-Lite2 int8 detector at
`phonecontrol/src/main/assets/models/efficientdet_lite2_int8.tflite`. The model runs locally and
includes COCO labels for everyday objects such as person, car, bicycle, bus, chair and dog.

- Source: `https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite2/int8/latest/efficientdet_lite2.tflite`
- SHA-256: `B3F50554CB0EA559E90328845F7D9BA4D13C8BFF372914D24E06BC8BB72FA896`
- Size: 7,515,971 bytes
- Model family: EfficientDet-Lite2 (448x448 input)
- License: Apache 2.0

The detector is an assistive cue, not a substitute for a cane, guide dog, situational awareness,
or safety-critical vehicle/pedestrian sensing. Device validation must cover daylight, low light,
motion blur, occlusion, multiple people and roadside scenes before a release.
