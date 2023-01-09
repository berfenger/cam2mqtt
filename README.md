# cam2mqtt

## Purpose

This package aims to implement a translation layer between camera interfaces 
(ONVIF, ad-hoc HTTP APIs, etc) and MQTT.

#### Why not use other software like [onvif2mqtt](https://github.com/dmitrif/onvif2mqtt)?

Software based on `onvif` node library is prone to reliability problems.
If a camera goes offline for a while, the library won't know the camera is offline
and won't recover nor fail (e.g. letting docker-compose restart the container).
Moreover, this software doesn't release resources reserved on the cameras (like ONVIF subscriptions)
which can cause random connection problems on some cameras.
These are severe problems for a security/alarm system.

## Features

This program is designed with reliability in mind. Every component is independent and tries
to recover by itself if any connection problem happens.

For now only two modules are implemented:
* ONVIF Events:
  * Motion events
  * AI powered object detection (only Reolink cameras on firmwares >= April 2022).
* Reolink:
  * Control
    * PTZ (absolute zoom)
    * Change night vision mode (auto, on, off)
    * Enable/disable IR lights (auto, off)
    * Change motion alarm sensitivity (old API)
    * Sync date time
    * Enable/disable Record (V1 & V2)
    * Enable/disable FTP (V1 & V2)
  * AI powered object detection
    * Person detection
    * Vehicle detection
    * Pet detection (not yet supported on some firmwares)
    * Face detection (not yet supported by firmware)

## Requirements
* Docker (if you want painless deployment)
* MQTT broker (e.g. Mosquitto)
* Al least one compatible camera

## Hardware Compatibility
* Reolink RLC-520, RLC-511W
* Reolink RLC-520A, RLC-511WA, RLC-810A, RLC-811A, RLC-822A (incl. people and vehicle AI detection)
* Other Reolink ONVIF cameras should work.
* Any other IP camera supporting ONVIF event subscriptions (webhook or pullpoint-subscription based) should work.

Feel free to try other cameras and let me know if it works so I can update this list.

## MQTT Protocol

##### Camera availability
    cam2mqtt/camera/{cameraId}/status online/offline

### Onvif module

##### Motion events
    cam2mqtt/camera/{cameraId}/event/onvif/motion on/off

##### AI Detection events (Reolink firmware >= 3.1.0.951, april 2022)
    cam2mqtt/camera/{cameraId}/event/onvif/object/people/detected on/off
    cam2mqtt/camera/{cameraId}/event/onvif/object/vehicle/detected on/off

### Reolink module

##### States
    cam2mqtt/camera/{cameraId}/state/reolink/nightvision auto/on/off
    cam2mqtt/camera/{cameraId}/state/reolink/irlights on/off
    cam2mqtt/camera/{cameraId}/state/reolink/motion/sensitivity 0-100
    cam2mqtt/camera/{cameraId}/state/reolink/ptz/zoom/absolute 0-100
    cam2mqtt/camera/{cameraId}/state/reolink/record on/off
    cam2mqtt/camera/{cameraId}/state/reolink/ftp on/off
    cam2mqtt/camera/{cameraId}/state/reolink/ai_detection_mode on_motion

##### Commands
    cam2mqtt/camera/{cameraId}/command/reolink/nightvision auto/on/off
    cam2mqtt/camera/{cameraId}/command/reolink/irlights on/off
    cam2mqtt/camera/{cameraId}/command/reolink/ftp on/off
    cam2mqtt/camera/{cameraId}/command/reolink/motion/sensitivity 0-100
    cam2mqtt/camera/{cameraId}/command/reolink/ptz/zoom/absolute 0-100
    cam2mqtt/camera/{cameraId}/command/reolink/record on/off
    cam2mqtt/camera/{cameraId}/command/reolink/ftp on/off

##### Motion events (AI detection)
    cam2mqtt/camera/{cameraId}/event/reolink/aidetection/people/detected on/off
    cam2mqtt/camera/{cameraId}/event/reolink/aidetection/vehicle/detected on/off
    cam2mqtt/camera/{cameraId}/event/reolink/aidetection/pet/detected on/off
    cam2mqtt/camera/{cameraId}/event/reolink/aidetection/face/detected on/off

## Clarification on Reolink's AI-based detection capabilities

Reolink cameras have 2 ways to communicate AI detection state.
 * HTTP API: Polling based. Supported by all "A" IP cameras.
 * Onvif subscription: Supported on some "A" cameras. Only those on firmware `>= 3.1.0.951, april 2022`. Most of the cameras don't have this firmware available as of December 2022.

For cameras on firmware `< 3.1.0.951, april 2022` you should use the `reolink` module with option `ai_detection_mode: on_motion` if using it in conjunction with `onvif` module (recommended), or `ai_detection_mode: continuous` if not using the `onvif` module. 

For cameras on firmware `>= 3.1.0.951, april 2022`, if using `reolink` module you should use `ai_detection_mode: off` (default behavior).

## Licensing
Copyright 2022 Arturo Casal

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
