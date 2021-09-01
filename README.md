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
* ONVIF:
  * Events: motion events
* Reolink:
  * Control
    * PTZ (absolute zoom)
    * Change night vision mode (auto, on, off)
    * Enable/disable IR lights (auto, off)
    * Change motion alarm sensitivity
    * Sync date time

## Requirements
* Docker (if you want painless deployment)
* MQTT broker (e.g. Mosquitto)
* Al least one compatible camera

## Hardware Compatibility
* Reolink RLC-520
* Reolink RLC-511w
* Reolink RLC-811A
* Other Reolink ONVIF cameras should work.
* Any other IP camera supporting ONVIF event subscriptions (webhook or pullpoint-subscription based) should work.

Feel free to try other cameras and let me know if it works so I can update this list.

## MQTT Protocol

##### Camera availability
    cam2mqtt/camera/{cameraId}/status online/offline

#### Onvif module

##### Motion events
    cam2mqtt/camera/{cameraId}/event/onvif/motion on/off

#### Reolink module

##### States
    cam2mqtt/camera/{cameraId}/state/reolink/nightvision auto/on/off
    cam2mqtt/camera/{cameraId}/state/reolink/irlights on/off
    cam2mqtt/camera/{cameraId}/state/reolink/ftp on/off
    cam2mqtt/camera/{cameraId}/state/reolink/motion/sensitivity 0-100
    cam2mqtt/camera/{cameraId}/state/reolink/ptz/zoom/absolute 0-100

##### Commands
    cam2mqtt/camera/{cameraId}/command/reolink/nightvision auto/on/off
    cam2mqtt/camera/{cameraId}/command/reolink/irlights on/off
    cam2mqtt/camera/{cameraId}/command/reolink/ftp on/off
    cam2mqtt/camera/{cameraId}/command/reolink/motion/sensitivity 0-100
    cam2mqtt/camera/{cameraId}/command/reolink/ptz/zoom/absolute 0-100
    

## Licensing
Copyright 2020 Arturo Casal

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
