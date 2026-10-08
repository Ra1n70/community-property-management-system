import React, { useEffect, useState } from "react";
import { api } from "./api";
import { confirmAction } from "./englishUi";
import "./locker-management.css";

export default function ManagerLockers() {
  const [lockers, setLockers] = useState([]);
  const [selected, setSelected] = useState(null);
  const [cells, setCells] = useState([]);
  const [location, setLocation] = useState("");
  const [editLocation, setEditLocation] = useState("");
  const [cellNumber, setCellNumber] = useState("");
  const [size, setSize] = useState("SMALL");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    api("/manager/lockers")
      .then(setLockers)
      .catch((err) => setError(err.message));
  }, []);

  async function showCells(locker) {
    setError("");
    try {
      const found = await api(`/manager/lockers/${locker.id}/cells`);
      setSelected(locker);
      setEditLocation(locker.location);
      setCells(found);
    } catch (err) {
      setError(err.message);
    }
  }

  async function createLocker(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const created = await api("/manager/lockers", {
        method: "POST",
        data: { location },
      });
      setLockers((current) => [...current, created]);
      setSelected(created);
      setEditLocation(created.location);
      setCells([]);
      setLocation("");
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function createCell(event) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      const created = await api(`/manager/lockers/${selected.id}/cells`, {
        method: "POST",
        data: { cellNumber, size },
      });
      setCells((current) => [...current, created]);
      setCellNumber("");
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function deleteLocker() {
    const locker = selected;
    if (!(await confirmAction({ title: `Delete locker ${locker.location}?`, message: "This cannot be undone. All cells must be deleted first.", confirmLabel: "Delete locker", danger: true }))) return;
    setBusy(true);
    setError("");
    try {
      await api(`/manager/lockers/${locker.id}`, { method: "DELETE" });
      setLockers(current => current.filter(item => item.id !== locker.id));
      setSelected(null);
      setCells([]);
      setEditLocation("");
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function deleteCell(cell) {
    if (!(await confirmAction({ title: `Delete cell ${cell.cellNumber}?`, message: "This cannot be undone. Cells with parcel or intake history cannot be deleted.", confirmLabel: "Delete cell", danger: true }))) return;
    setBusy(true);
    setError("");
    try {
      await api(`/manager/lockers/${selected.id}/cells/${cell.id}`, { method: "DELETE" });
      setCells(current => current.filter(item => item.id !== cell.id));
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function toggleCell(cell) {
    setBusy(true);
    setError("");
    try {
      const updated = await api(
        `/manager/lockers/${selected.id}/cells/${cell.id}`,
        {
          method: "PATCH",
          data: { disabled: cell.status !== "DISABLED" },
        },
      );
      setCells((current) =>
        current.map((item) => (item.id === updated.id ? updated : item)),
      );
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }
  async function updateLocation(event) {
    event.preventDefault();
    setBusy(true);
    setError("");

    try {
      const updated = await api(`/manager/lockers/${selected.id}`, {
        method: "PATCH",
        data: { location: editLocation.trim() },
      });
      setSelected(updated);
      setLockers((current) =>
        current.map((locker) => (locker.id === updated.id ? updated : locker)),
      );
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  async function toggleLockerStatus() {
    setBusy(true);
    setError("");

    try {
      const updated = await api(`/manager/lockers/${selected.id}`, {
        method: "PATCH",
        data: {
          status: selected.status === "ACTIVE" ? "DISABLED" : "ACTIVE",
        },
      });
      setSelected(updated);
      setLockers((current) =>
        current.map((locker) => (locker.id === updated.id ? updated : locker)),
      );
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }
  async function updateCellSize(cell, newSize) {
    setBusy(true);
    setError("");

    try {
      const updated = await api(
        `/manager/lockers/${selected.id}/cells/${cell.id}`,
        {
          method: "PATCH",
          data: { size: newSize },
        },
      );
      setCells((current) =>
        current.map((item) => (item.id === updated.id ? updated : item)),
      );
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="card locker-manager">
      <span className="eyebrow">PACKAGE LOCKERS</span>
      <h2>Lockers & cells <span className="count">{lockers.length}</span></h2>
      <p className="hint">Manage package storage, available space and locker locations.</p>

      {error && (
        <p className="message error" role="alert">
          {error}
        </p>
      )}

      <form className="locker-create" onSubmit={createLocker}>
        <h3>Add Locker</h3>
        <div className="grid">
          <label>
            Location
            <input
              value={location}
              onChange={(event) => setLocation(event.target.value)}
              maxLength={200}
              required
            />
          </label>
        </div>
        <button className="primary" disabled={busy}>
          Add Locker
        </button>
      </form>

      <h3>Existing Lockers</h3>
      {lockers.length === 0 ? (
        <p className="empty">No lockers yet.</p>
      ) : (
        <div className="locker-list">
          {lockers.map((locker) => (
            <button
              key={locker.id}
              className="locker-choice"
              aria-pressed={selected?.id === locker.id}
              type="button"
              disabled={busy}
              onClick={() => showCells(locker)}
            >
              <span><strong>{locker.location}</strong></span><span className={`badge locker-status-${locker.status}`}>{locker.status === "ACTIVE" ? "Active" : "Disabled"}</span>
            </button>
          ))}
        </div>
      )}

      {selected && (
        <div className="locker-editor">
          <div className="section-head"><div><span className="eyebrow">SELECTED LOCKER</span><h3>Cells in {selected.location}</h3></div><span className="badge">{cells.length} cells · {cells.filter(c => c.status === "AVAILABLE").length} available</span></div>
          <p><a className="locker-entry" href={`/locker-panel?lockerId=${selected.id}`} target="_blank" rel="noopener noreferrer">Open Locker Panel ↗</a></p>
          <form className="locker-location" onSubmit={updateLocation}>
            <label>
              Locker Location
              <input
                value={editLocation}
                onChange={(event) => setEditLocation(event.target.value)}
                maxLength={200}
                required
              />
            </label>
            <button type="submit" disabled={busy}>
              Save Location
            </button>
          <button type="button" disabled={busy} onClick={toggleLockerStatus}>
            {selected.status === "ACTIVE" ? "Disable Locker" : "Enable Locker"}
          </button><button type="button" className="locker-delete" disabled={busy} onClick={deleteLocker}>Delete Locker</button></form>
          <p className="hint">Delete all unused cells before deleting a locker. Lockers with parcel or intake history should be disabled instead.</p>

          <form className="locker-create" onSubmit={createCell}>
            <h3>Add a cell</h3>
            <div className="grid">
              <label>
                Cell Number
                <input
                  value={cellNumber}
                  onChange={(event) => setCellNumber(event.target.value)}
                  maxLength={30}
                  required
                />
              </label>
              <label>
                Size
                <select
                  value={size}
                  onChange={(event) => setSize(event.target.value)}
                >
                  <option value="SMALL">Small</option>
                  <option value="MEDIUM">Medium</option>
                  <option value="LARGE">Large</option>
                </select>
              </label>
            </div>
            <button className="primary" disabled={busy}>
              Add Cell
            </button>
          </form>

          <p className="hint">Only unused cells can be deleted. Disable cells with parcel or intake history to keep their records.</p>
          {cells.length === 0 ? (
            <p className="empty">No cells in this locker.</p>
          ) : (
            <div className="table-wrap">
              <table>
                <thead>
                  <tr>
                    <th>Cell</th>
                    <th>Size</th>
                    <th>Status</th>
                    <th>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {cells.map((cell) => (
                    <tr key={cell.id}>
                      <td><strong>{cell.cellNumber}</strong></td>
                      <td>
                        {cell.status === "AVAILABLE" ||
                        cell.status === "DISABLED" ? (
                          <select
                            value={cell.size}
                            disabled={busy}
                            onChange={(event) =>
                              updateCellSize(cell, event.target.value)
                            }
                            aria-label={`Size for cell ${cell.cellNumber}`}
                          >
                            <option value="SMALL">Small</option>
                            <option value="MEDIUM">Medium</option>
                            <option value="LARGE">Large</option>
                          </select>
                        ) : (
                          cell.size
                        )}
                      </td>
                      <td><span className={`badge locker-status-${cell.status}`}>{cell.status.charAt(0) + cell.status.slice(1).toLowerCase()}</span></td>
                      <td>
                        {(cell.status === "AVAILABLE" ||
                          cell.status === "DISABLED") && (
                          <div className="actions"><button
                            type="button"
                            disabled={busy}
                            onClick={() => toggleCell(cell)}
                          >
                            {cell.status === "DISABLED" ? "Enable" : "Disable"}
                          </button><button className="locker-delete" type="button" disabled={busy} onClick={() => deleteCell(cell)} aria-label={`Delete cell ${cell.cellNumber}`}>Delete</button></div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
