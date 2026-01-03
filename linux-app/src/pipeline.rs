pub fn make_sink_pipeline_str(device: &str) -> String {
    format!(
        "appsrc name=mysrc is-live=true format=time min-latency=0 caps=\"video/x-raw,format=I420,width=1920,height=1080,framerate=30/1,pixel-aspect-ratio=1/1\" ! videoconvert ! videoscale ! video/x-raw,format=YUY2,width=1920,height=1080 ! v4l2sink device={} sync=false",
        device
    )
}

pub fn make_saver_pipeline_str() -> String {
    "videotestsrc pattern=smpte ! videoscale ! video/x-raw,format=I420,width=1920,height=1080 ! appsink name=saver sync=false drop=true max-buffers=1".to_string()
}

pub fn make_source_pipeline_str(port: u16, codec: &str) -> String {
    let parser_decoder = if codec == "h264" {
        "h264parse ! avdec_h264"
    } else {
        "h265parse ! avdec_h265"
    };

    format!(
        "srtsrc uri=srt://:{}?mode=listener&latency=20&poll-timeout=100 ! tsdemux ! {} max-threads=4 ! videoflip method=clockwise ! videoconvert ! video/x-raw,pixel-aspect-ratio=1/1 ! videoscale add-borders=true ! video/x-raw,format=I420,width=1920,height=1080,pixel-aspect-ratio=1/1 ! appsink name=mysink sync=false drop=true max-buffers=1",
        port, parser_decoder
    )
}

pub fn make_mjpeg_pipeline_str(url: &str) -> String {
    format!(
        "souphttpsrc location={} is-live=true do-timestamp=true keep-alive=true ! multipartdemux ! jpegdec ! videoconvert ! video/x-raw,pixel-aspect-ratio=1/1 ! videoscale add-borders=true ! video/x-raw,format=I420,width=1920,height=1080,pixel-aspect-ratio=1/1 ! appsink name=mysink sync=false drop=true max-buffers=1",
        url
    )
}

pub fn make_rtsp_pipeline_str(url: &str) -> String {
    format!(
        "rtspsrc location={} latency=20 ! rtph264depay ! h264parse ! avdec_h264 ! videoflip method=clockwise ! videoconvert ! video/x-raw,pixel-aspect-ratio=1/1 ! videoscale add-borders=true ! video/x-raw,format=I420,width=1920,height=1080,pixel-aspect-ratio=1/1 ! appsink name=mysink sync=false drop=true max-buffers=1",
        url
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_sink_pipeline_gen() {
        let s = make_sink_pipeline_str("/dev/video20");
        assert!(s.contains("/dev/video20"));
        assert!(s.contains("v4l2sink"));
    }

    #[test]
    fn test_source_pipeline_gen() {
        let h264 = make_source_pipeline_str(5000, "h264");
        assert!(h264.contains("5000"));
        assert!(h264.contains("h264parse"));
        assert!(h264.contains("videoflip method=clockwise"));

        let h265 = make_source_pipeline_str(5001, "h265");
        assert!(h265.contains("5001"));
        assert!(h265.contains("h265parse"));
        assert!(h264.contains("videoflip method=clockwise"));
    }

    #[test]
    fn test_rtsp_pipeline_gen() {
        let url = "rtsp://10.0.0.6:8554/live.sdp";
        let s = make_rtsp_pipeline_str(url);
        assert!(s.contains(url));
        assert!(s.contains("rtspsrc"));
        assert!(s.contains("videoflip method=clockwise"));
    }
}
