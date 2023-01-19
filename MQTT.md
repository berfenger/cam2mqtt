## cam2mqtt MQTT Protocol

##### Camera availability
    cam2mqtt/camera/{cameraId}/status online/offline

### ONVIF module

##### ONVIF motion events
    cam2mqtt/camera/{cameraId}/event/onvif/motion on/off

##### Reolink AI Detection events (Reolink firmware >= 3.1.0.951, april 2022)
    cam2mqtt/camera/{cameraId}/event/onvif/object/people/detected on/off
    cam2mqtt/camera/{cameraId}/event/onvif/object/vehicle/detected on/off

##### Reolink Visitor events (for Reolink Video Doorbell PoE/Wifi)
    cam2mqtt/camera/{cameraId}/event/onvif/visitor on/off

### Reolink module

##### States
    cam2mqtt/camera/{cameraId}/state/reolink/nightvision auto/on/off
    cam2mqtt/camera/{cameraId}/state/reolink/irlights on/off
    cam2mqtt/camera/{cameraId}/state/reolink/motion/sensitivity 0-100
    cam2mqtt/camera/{cameraId}/state/reolink/ptz/zoom/absolute 0-100
    cam2mqtt/camera/{cameraId}/state/reolink/record on/off
    cam2mqtt/camera/{cameraId}/state/reolink/ftp on/off
    cam2mqtt/camera/{cameraId}/state/reolink/ai_detection_mode off/on_motion/continuous
    cam2mqtt/camera/{cameraId}/state/reolink/audio/volume 0-100
    cam2mqtt/camera/{cameraId}/state/reolink/spotlight/state on/off
    cam2mqtt/camera/{cameraId}/state/reolink/spotlight/brightness 0-100

##### Commands
    cam2mqtt/camera/{cameraId}/command/reolink/nightvision auto/on/off
    cam2mqtt/camera/{cameraId}/command/reolink/irlights on/off
    cam2mqtt/camera/{cameraId}/command/reolink/ftp on/off
    cam2mqtt/camera/{cameraId}/command/reolink/motion/sensitivity 0-100
    cam2mqtt/camera/{cameraId}/command/reolink/ptz/zoom/absolute 0-100
    cam2mqtt/camera/{cameraId}/command/reolink/record on/off
    cam2mqtt/camera/{cameraId}/command/reolink/ftp on/off
    cam2mqtt/camera/{cameraId}/command/reolink/audio/volume 0-100
    cam2mqtt/camera/{cameraId}/command/reolink/alarm/play on/off/1-100
    cam2mqtt/camera/{cameraId}/command/reolink/spotlight/state on/off
    cam2mqtt/camera/{cameraId}/command/reolink/spotlight/brightness 0-100


##### Motion events (AI detection)
    cam2mqtt/camera/{cameraId}/event/reolink/aidetection/people/detected on/off
    cam2mqtt/camera/{cameraId}/event/reolink/aidetection/vehicle/detected on/off
    cam2mqtt/camera/{cameraId}/event/reolink/aidetection/pet/detected on/off
    cam2mqtt/camera/{cameraId}/event/reolink/aidetection/face/detected on/off
 
