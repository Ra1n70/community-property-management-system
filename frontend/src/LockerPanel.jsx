import React, { useState, useEffect } from "react";
import { api } from "./api";
import QrScanner from "./QrScanner";
import "./locker-management.css";

// Package kiosk: residents pick up with a pickup code; couriers store packages with their personal store code.
export default function LockerPanel() {
  const [lockerId, setLockerId] = useState(() => { const id = new URLSearchParams(window.location.search).get("lockerId"); return /^[1-9]\d*$/.test(id || "") ? id : ""; });
  const [locations, setLocations] = useState([]);
  useEffect(() => { api("/locker-panel/locations").then(setLocations).catch(err => setError(err.message)); }, []);
  const [view, setView] = useState("home");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  // Resident pickup
  const [code, setCode] = useState("");
  const [pickupResult, setPickupResult] = useState(null);
  const [scanning, setScanning] = useState(false);

  // Courier intake
  const [storeCode, setStoreCode] = useState("");
  const [courier, setCourier] = useState(null);
  const [room, setRoom] = useState("");
  const [recipients, setRecipients] = useState([]);
  const [residentId, setResidentId] = useState("");
  const [packageSize, setPackageSize] = useState("SMALL");
  const [session, setSession] = useState(null);
  const [stored, setStored] = useState(null);

  function resetPackage() {
    setRoom("");
    setRecipients([]);
    setResidentId("");
    setPackageSize("SMALL");
    setSession(null);
    setStored(null);
    setError("");
  }

  function goHome() {
    resetPackage();
    setView("home");
    setCode("");
    setPickupResult(null);
    setScanning(false);
    setStoreCode("");
    setCourier(null);
  }

  async function run(work) {
    setBusy(true);
    setError("");
    try { await work(); } catch (err) { setError(err.message); } finally { setBusy(false); }
  }

  function pickup(event) {
    event.preventDefault();
    setPickupResult(null);
    run(async () => {
      const response = await api(`/locker-panel/${lockerId}/pickup`, { method: "POST", data: { pickupCode: code } });
      setPickupResult(response);
      if (response.result === "SUCCESS") setCode("");
    });
  }

  function verifyCourier(event) {
    event.preventDefault();
    run(async () => {
      setCourier(await api(`/locker-panel/${lockerId}/courier/verify`, { method: "POST", data: { storeCode } }));
      setView("courier");
    });
  }

  function lookupRoom(event) {
    event.preventDefault();
    setRecipients([]);
    setResidentId("");
    run(async () => {
      const found = await api(`/locker-panel/${lockerId}/courier/residents`, { method: "POST", data: { storeCode, room: room.trim() } });
      setRecipients(found);
      if (found.length === 1) setResidentId(String(found[0].residentId));
    });
  }

  function startIntake() {
    if (!residentId) return;
    run(async () => {
      setSession(await api(`/locker-panel/${lockerId}/intake/start`, {
        method: "POST",
        data: { storeCode, room: room.trim(), residentId: Number(residentId), packageSize },
      }));
      setView("deposit");
    });
  }

  function confirmIntake() {
    if (!session) return;
    run(async () => {
      setStored(await api(`/locker-panel/${lockerId}/intake/confirm`, { method: "POST", data: { storeCode, sessionToken: session.sessionToken } }));
      setSession(null);
      setView("stored");
    });
  }

  function pickupMessage(result) {
    if (result.result === "LOCKED") {
      return `Too many failed attempts. Try again after ${new Date(result.lockedUntil).toLocaleTimeString("en-US")}.`;
    }
    const messages = {
      INVALID_CODE: "Invalid pickup code.",
      ALREADY_USED: "This pickup code has already been used.",
      EXPIRED: "This pickup code has expired. Please contact management.",
      LOCKER_DISABLED: "This locker is currently unavailable.",
    };
    return messages[result.result] ?? "Pickup could not be completed.";
  }

  const location = locations.find(l => String(l.id) === lockerId);
  return (
    <main className="kiosk">
      <section className="card">
        <div className="section-head">
          <div>
            <span className="eyebrow">PACKAGE LOCKER KIOSK</span>
            <h1>{location ? location.location : "Package Locker"}</h1>
          </div>
          {view !== "home" && <button type="button" onClick={goHome}>{courier ? "Finish · Sign out" : "Back to Home"}</button>}
        </div>

        {view === "home" && <>
          <label>
            Locker location
            <select value={lockerId} disabled={busy} onChange={event => { setLockerId(event.target.value); setError(""); }}>
              <option value="">Select a location</option>
              {locations.map(locker => <option key={locker.id} value={locker.id}>{locker.location} · {locker.community}</option>)}
            </select>
          </label>
          <div className="kiosk-choices">
            <button type="button" className="kiosk-choice" disabled={!lockerId} onClick={() => setView("pickup")}>
              <span aria-hidden="true">📦</span><strong>I’m a resident</strong><small>Pick up a package with your pickup code</small>
            </button>
            <button type="button" className="kiosk-choice" disabled={!lockerId} onClick={() => setView("courier-code")}>
              <span aria-hidden="true">🚚</span><strong>I’m a courier</strong><small>Store a package with your personal store code</small>
            </button>
          </div>
        </>}

        {view === "pickup" && (
          <form onSubmit={pickup}>
            <h2>Pick up your package</h2>
            <p>Enter the six-digit code from My Packages in your resident account.</p>
            <label>
              Pickup code
              <input inputMode="numeric" pattern="[0-9]{6}" maxLength={6} value={code} required
                onChange={event => setCode(event.target.value.replace(/\D/g, "").slice(0, 6))} />
            </label>
            <div className="actions">
              <button type="button" onClick={() => { setError(""); setScanning(value => !value); }}>
                {scanning ? "Stop camera" : "Scan QR code"}
              </button>
              <button className="primary" disabled={busy || code.length !== 6}>{busy ? "Checking…" : "Open locker"}</button>
            </div>
            {scanning && (
              <QrScanner
                onScan={value => { setCode(value); setScanning(false); setError(""); }}
                onError={message => { setScanning(false); setError(message); }}
              />
            )}
          </form>
        )}

        {view === "courier-code" && (
          <form onSubmit={verifyCourier}>
            <h2>Courier sign-in</h2>
            <p>Enter the eight-digit store code from your delivery company. Independent couriers get their code from property management.</p>
            <label>
              Store code
              <input inputMode="numeric" autoComplete="off" pattern="[0-9]{8}" maxLength={8} value={storeCode} required autoFocus
                onChange={event => setStoreCode(event.target.value.replace(/\D/g, "").slice(0, 8))} />
            </label>
            <button className="primary" disabled={busy || storeCode.length !== 8}>{busy ? "Checking…" : "Continue"}</button>
            <div className="help-box">
              <strong>No store code?</strong>
              <p>Leave the package with the property office. Staff will register it for the resident, or give you a temporary code that works for the rest of today.</p>
            </div>
          </form>
        )}

        {courier && ["courier", "deposit", "stored"].includes(view) && (
          <p className="kiosk-courier"><span aria-hidden="true">🚚</span> {courier.name} · {courier.companyName || "Independent courier"}</p>
        )}

        {view === "courier" && (
          <div>
            <h2>Store a package</h2>
            <form onSubmit={lookupRoom}>
              <label>
                Room / unit
                <input value={room} maxLength={30} required
                  onChange={event => { setRoom(event.target.value); setRecipients([]); setResidentId(""); }} />
              </label>
              <button disabled={busy || !room.trim()}>{busy ? "Checking…" : "Find resident"}</button>
            </form>

            {recipients.length > 0 && (
              <div className="detail">
                <p>Confirm the recipient for room {room}:</p>
                <label>
                  Recipient
                  <select value={residentId} onChange={event => setResidentId(event.target.value)}>
                    <option value="">Select a resident</option>
                    {recipients.map(person => <option key={person.residentId} value={person.residentId}>{person.maskedName}</option>)}
                  </select>
                </label>
                <label>
                  Package size
                  <select value={packageSize} onChange={event => setPackageSize(event.target.value)}>
                    <option value="SMALL">Small</option>
                    <option value="MEDIUM">Medium</option>
                    <option value="LARGE">Large</option>
                  </select>
                </label>
                <button type="button" className="primary" disabled={busy || !residentId} onClick={startIntake}>
                  {busy ? "Assigning…" : "Assign a cell"}
                </button>
              </div>
            )}
          </div>
        )}

        {view === "deposit" && session && (
          <div>
            <h2>Cell {session.cellNumber} is open</h2>
            <p>Location: {session.lockerLocation}</p>
            <p>Place the package inside and close the door. Complete before {new Date(session.expiresAt).toLocaleTimeString("en-US")}.</p>
            <button type="button" className="primary" disabled={busy} onClick={confirmIntake}>
              {busy ? "Saving…" : "Package stored · Door closed"}
            </button>
          </div>
        )}

        {view === "stored" && stored && (
          <div className="message" role="status">
            <h2>Package stored successfully</h2>
            <p>Cell {stored.cellNumber} · {stored.lockerLocation}</p>
            <p>Pickup details are now available in the resident’s My Packages.</p>
            <div className="actions">
              <button type="button" className="primary" onClick={() => { resetPackage(); setView("courier"); }}>Store another package</button>
              <button type="button" onClick={goHome}>Finish · Sign out</button>
            </div>
          </div>
        )}

        {error && <p className="message error" role="alert">{error}</p>}
        {pickupResult?.result === "SUCCESS" && (
          <p className="message" role="status">Cell {pickupResult.cellNumber} is open. Please collect your package.</p>
        )}
        {pickupResult && pickupResult.result !== "SUCCESS" && (
          <p className="message error" role="alert">{pickupMessage(pickupResult)}</p>
        )}
      </section>
    </main>
  );
}
