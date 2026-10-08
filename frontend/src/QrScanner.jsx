import React, { useEffect, useRef } from "react";
import { BrowserQRCodeReader } from "@zxing/browser";

export default function QrScanner({ onScan, onError }) {
  const videoRef = useRef(null);
  const callbacks = useRef({ onScan, onError });
  callbacks.current = { onScan, onError };

  useEffect(() => {
    let stopped = false;
    let controls;

    const reader = new BrowserQRCodeReader();

    reader
      .decodeFromVideoDevice(
        undefined,
        videoRef.current,
        (result, _error, scanControls) => {
          if (!result || stopped) return;

          stopped = true;
          scanControls.stop();

          const value = result.getText().trim();
          if (/^\d{6}$/.test(value)) {
            callbacks.current.onScan(value);
          } else {
            callbacks.current.onError(
              "QR code must contain a six-digit pickup code.",
            );
          }
        },
      )
      .then((scanControls) => {
        if (stopped) scanControls.stop();
        else controls = scanControls;
      })
      .catch((err) => {
        if (!stopped) {
          callbacks.current.onError(
            err.message || "Camera could not be opened.",
          );
        }
      });

    return () => {
      stopped = true;
      controls?.stop();
    };
  }, []);

  return (
    <div>
      <p>Point the camera at your pickup QR code.</p>
      <video
        ref={videoRef}
        muted
        playsInline
        style={{ width: "100%", maxWidth: 360 }}
      />
    </div>
  );
}
