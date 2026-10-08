import {portalUrl,isCancelled} from './api';
import Pager from "./Pager";
import { keepInView } from "./keepInView";
import EnglishDateTime from "./EnglishDateTime";
import React, { useEffect, useState, useRef } from "react";
import { api } from "./api";
import "./maintenance.css";
const statusLabels = {
  PENDING: "Awaiting review",
  ACCEPTED: "Assigned",
  IN_PROGRESS: "In progress",
  PENDING_CONFIRMATION: "Awaiting resident confirmation",
  COMPLETED: "Completed",
  REJECTED: "Rejected",
  UNABLE_TO_FIX: "Could not fix · needs manager",
  UNRESOLVED: "Closed unresolved",
};
const steps = ["Submitted", "Assigned", "In progress", "Awaiting confirmation", "Completed"];
// Where each status sits on the five-step path, and whether the request stopped there.
const stage = {
  PENDING: { at: 0 }, ACCEPTED: { at: 1 }, IN_PROGRESS: { at: 2 }, PENDING_CONFIRMATION: { at: 3 },
  COMPLETED: { at: 4, done: true }, REJECTED: { at: 1, failed: "Rejected" },
  UNABLE_TO_FIX: { at: 3, failed: "Could not fix" },
  // Closed after "could not fix": the confirmation step failed too, and the last step shows the closure.
  UNRESOLVED: { at: 4, failed: "Closed unresolved", failedSteps: { 3: "Could not fix" } },
};
const stepEvents = [["SUBMITTED"], ["ASSIGNED", "REASSIGNED", "REJECT"], ["START", "REOPEN"], ["RESOLVE", "UNABLE_TO_FIX"], ["CONFIRM", "CLOSE"]];
function waitingFor(t, resident) {
  const who = t.assigneeName || "the assigned worker";
  return {
    PENDING: "Waiting for property management to review and assign this request.",
    ACCEPTED: `Waiting for ${who} to start work.`,
    IN_PROGRESS: `${who} is working on this request.`,
    PENDING_CONFIRMATION: resident ? "Waiting for you to confirm the repair." : "Waiting for the resident to confirm the repair.",
    COMPLETED: "The resident confirmed the repair.",
    REJECTED: "Property management rejected this request.",
    UNABLE_TO_FIX: `${who} could not fix it. Waiting for property management to reassign or close the request.`,
    UNRESOLVED: "Property management closed this request as unresolved.",
  }[t.status];
}
function MiniProgress({ status }) {
  const s = stage[status] || { at: 0 };
  return <span className={`maintenance-mini ${s.failed ? "failed" : ""}`} aria-hidden="true">
    {steps.map((_, i) => <i key={i} className={s.failedSteps?.[i] ? "current" : i < s.at || (i === s.at && s.done) ? "done" : i === s.at ? "current" : ""} />)}
  </span>;
}
function ProgressTracker({ ticket, history, resident }) {
  const s = stage[ticket.status] || { at: 0 };
  const latest = (actions) => [...history].reverse().find((e) => actions.includes(e.action));
  return <div className={`maintenance-progress ${s.failed ? "failed" : ""}`}>
    <ol>
      {steps.map((label, i) => {
        const event = i <= s.at ? latest(stepEvents[i]) : null;
        const earlierFailure = s.failedSteps?.[i];
        const state = earlierFailure ? "failed" : i < s.at || (i === s.at && s.done) ? "done" : i === s.at ? (s.failed ? "failed" : "current") : "todo";
        return <li key={label} className={state} aria-current={state === "current" ? "step" : undefined}>
          <span className="maintenance-step-dot" aria-hidden="true">{state === "done" ? "✓" : state === "failed" ? "!" : i + 1}</span>
          <strong>{earlierFailure || (state === "failed" ? s.failed : label)}</strong>
          {event && <small>{date(event.createdAt)} · {event.actorName}</small>}
        </li>;
      })}
    </ol>
    <p className="maintenance-waiting" role="status">{waitingFor(ticket, resident)}</p>
  </div>;
}
const date = (v) => (v ? new Date(v).toLocaleString("en-US") : "Not specified");
const fields = (e) => Object.fromEntries(new FormData(e.currentTarget));
function localDateKey(value) {
  const time = new Date(value);

  if (Number.isNaN(time.getTime())) return "";

  const year = time.getFullYear();
  const month = String(time.getMonth() + 1).padStart(2, "0");
  const day = String(time.getDate()).padStart(2, "0");

  return `${year}-${month}-${day}`;
}
// Start of a local calendar day as an instant; days=1 gives the start of the next day.
function localDayStart(value, days = 0) {
  const time = new Date(`${value}T00:00:00`);
  time.setDate(time.getDate() + days);
  return time.toISOString();
}
const PAGE_SIZE = 20;
function validDateFilter(value) {
  if (!value) return true;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false;
  return localDateKey(`${value}T12:00:00`) === value;
}
// focus = { id, seq } opens that request (e.g. from Search); seq changes when the same request is opened again.
export default function MaintenancePanel({ user, focus }) {
  const [rows, setRows] = useState([]),
    [selected, setSelected] = useState(null),
    [history, setHistory] = useState([]),
    [assignees, setAssignees] = useState([]),
    [busy, setBusy] = useState(false),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [filter, setFilter] = useState(""),
    [creating, setCreating] = useState(false),
    [ready, setReady] = useState(false);
  const [photos, setPhotos] = useState([]);
  const [description, setDescription] = useState("");
  const [priorityFilter, setPriorityFilter] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("");
  const [assigneeFilter, setAssigneeFilter] = useState("");
  const [submittedFrom, setSubmittedFrom] = useState("");
  const [submittedTo, setSubmittedTo] = useState("");
  const [listError, setListError] = useState("");

  const invalidDateRange =
    Boolean(submittedFrom && submittedTo) && submittedFrom > submittedTo;
  const invalidDateInput = !validDateFilter(submittedFrom) || !validDateFilter(submittedTo);
  const photoInput = useRef(null);
  const [previews, setPreviews] = useState([]);
  useEffect(() => {
    const urls = photos.map((file) => URL.createObjectURL(file));
    setPreviews(urls);
    return () => urls.forEach((url) => URL.revokeObjectURL(url));
  }, [photos]);
  function addPhotos(e) {
    const added = Array.from(e.target.files || []);
    e.target.value = "";
    if (photos.length + added.length > 3) {
      setError("Upload at most 3 photos.");
      return;
    }
    if (
      added.some(
        (f) =>
          !["image/jpeg", "image/png"].includes(f.type) ||
          f.size > 5 * 1024 * 1024,
      )
    ) {
      setError("Each photo must be a JPG or PNG of at most 5 MB.");
      return;
    }
    setError("");
    setPhotos((old) => [...old, ...added]);
  }
  const manager = user.role === "MANAGER",
    resident = user.role === "RESIDENT";
  const labels = {
    ...statusLabels,
    PENDING_CONFIRMATION: resident
      ? "Awaiting your confirmation"
      : "Awaiting resident confirmation",
  };
  async function run(fn) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await fn();
    } catch (e) {
      setError(e.message);
    } finally {
      setBusy(false);
    }
  }
  const [pageNo, setPageNo] = useState(0),
    [pageData, setPageData] = useState(null),
    [reloadKey, setReloadKey] = useState(0),
    [listLoading, setListLoading] = useState(false),
    [categories, setCategories] = useState([]),
    [assignedWorkers, setAssignedWorkers] = useState([]);
  // Filters run on the server, which returns one page at a time.
  const params = new URLSearchParams({ page: pageNo, size: PAGE_SIZE });
  if (filter) params.set("status", filter);
  if (manager) {
    if (priorityFilter) params.set("priority", priorityFilter);
    if (categoryFilter) params.set("category", categoryFilter);
    if (assigneeFilter === "unassigned") params.set("unassigned", "true");
    else if (assigneeFilter) params.set("assigneeId", assigneeFilter);
    if (!invalidDateRange && !invalidDateInput) {
      if (submittedFrom) params.set("from", localDayStart(submittedFrom));
      if (submittedTo) params.set("to", localDayStart(submittedTo, 1));
    }
  }
  const listQuery = params.toString();
  // A newer filter or page cancels the request still running, so older results never replace newer ones.
  useEffect(() => {
    const request = new AbortController();
    setListLoading(true);
    api(`/maintenance/page?${listQuery}`, { signal: request.signal })
      .then((data) => {
        // The last page can empty out when requests change status; show the new last page instead.
        if (!data.items.length && data.page > 0 && data.totalPages > 0) {
          setPageNo(data.totalPages - 1);
          return;
        }
        setRows(data.items);
        setPageData(data);
        setCategories(data.categories);
        setAssignedWorkers(data.assignees);
        setListError("");
        setReady(true);
        setListLoading(false);
      })
      .catch((error) => {
        if (isCancelled(error)) return;
        setListError("Could not load requests. Please retry.");
        setListLoading(false);
      });
    return () => request.abort();
  }, [listQuery, reloadKey]);
  const reloadList = () => setReloadKey((key) => key + 1);
  function filterBy(setter) {
    return (e) => {
      setter(e.target.value);
      setPageNo(0);
    };
  }
  async function openTicket(id) {
    const t = await api(`/maintenance/${id}`);
    setSelected(t);
    setHistory(await api(`/maintenance/${id}/history`));
  }
  async function refresh() {
    reloadList();
    if (manager) setAssignees(await api("/maintenance/assignees"));
    if (!selected) return;
    try {
      await openTicket(selected.id);
    } catch (error) {
      if (error.status !== 404) throw error;
      setSelected(null);
    }
  }
  const detailRef = useRef(null);
  useEffect(() => {
    if (manager) run(async () => setAssignees(await api("/maintenance/assignees")));
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  // Search can open a request that is not on the current page, so it is loaded by id.
  useEffect(() => {
    if (!focus?.id) return;
    run(() =>
      openTicket(focus.id).catch((error) => {
        if (error.status === 404) throw new Error("That maintenance request is no longer available.");
        throw error;
      }),
    );
  }, [focus?.seq]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (focus?.id && selected?.id === focus.id)
      return keepInView(() => detailRef.current, { block: "start" });
  }, [selected?.id, focus?.seq]); // eslint-disable-line react-hooks/exhaustive-deps
  async function choose(t) {
    setSelected(t);
    setHistory(await api(`/maintenance/${t.id}/history`));
  }
  async function action(name, data = {}) {
    const t = await api(`/maintenance/${selected.id}/${name}`, {
      method: "POST",
      data: { ...data, version: selected.version },
    });
    setSelected(t);
    setHistory(await api(`/maintenance/${t.id}/history`));
    reloadList();
    setNotice("Request updated.");
  }
  const visible = rows;
  const worker = selected && selected.assigneeId === user.id;
  return (
    <div className="maintenance-module">
      {error && (
        <div className="message error" role="alert">
          {error}
        </div>
      )}
      {notice && (
        <div className="message" role="status">
          {notice}
        </div>
      )}
      <section className="card">
        <div className="section-head">
          <div>
            <span className="eyebrow">COMMUNITY CARE</span>
            <h2>
              {resident
                ? "My maintenance requests"
                : manager
                  ? "Maintenance requests"
                  : "Assigned work orders"}
            </h2>
            <p>
              {resident
                ? "Report an issue and follow its progress."
                : manager
                  ? "Review requests, assign work and track completion."
                  : "Start your assigned work and share the result."}
            </p>
          </div>
          <div className="actions">
            <button disabled={busy} onClick={() => run(refresh)}>
              Refresh
            </button>
            {resident && (
              <button
                className="primary"
                disabled={busy}
                onClick={() => setCreating(!creating)}
              >
                {creating ? "Close form" : "New request"}
              </button>
            )}
          </div>
        </div>
        {creating && (
          <form
            className="maintenance-form"
            onSubmit={(e) => {
              e.preventDefault();
              const data = fields(e);
              const cleanDescription = String(data.description || "").trim();

              if (!cleanDescription) {
                setError("Please describe the issue.");
                return;
              }

              if (String(data.description || "").length > 3000) {
                setError("Description must be at most 3000 characters.");
                return;
              }

              data.description = cleanDescription;
              run(async () => {
                const form = new FormData();
                form.append(
                  "request",
                  new Blob(
                    [
                      JSON.stringify({
                        ...data,
                        preferredTime: data.preferredTime
                          ? new Date(data.preferredTime).toISOString()
                          : null,
                      }),
                    ],
                    { type: "application/json" },
                  ),
                );
                photos.forEach((file) => form.append("photos", file));
                const t = await api("/maintenance", {
                  method: "POST",
                  data: form,
                });
                setPhotos([]);
                setDescription("");
                await openTicket(t.id);
                setPageNo(0);
                reloadList();
                setCreating(false);
                setNotice("Request submitted for review.");
              });
            }}
          >
            <div className="grid">
              <label>
                Category
                <select name="category">
                  <option>Plumbing</option>
                  <option>Electrical</option>
                  <option>Heating / cooling</option>
                  <option>Appliance</option>
                  <option>Other</option>
                </select>
              </label>
              <label>
                Location
                <input
                  name="location"
                  required
                  maxLength={200}
                  placeholder="Room or shared area"
                />
              </label>
            </div>
            <label>
              Description
              <textarea
                name="description"
                required
                maxLength={3000}
                value={description}
                disabled={busy}
                aria-describedby="maintenance-description-count"
                onChange={(event) => {
                  const value = event.target.value;
                  setDescription(value);

                  event.target.setCustomValidity(
                    value.trim() ? "" : "Please describe the issue.",
                  );
                }}
              />
            </label>
            <p id="maintenance-description-count" className="hint">
              {description.length} / 3000 characters
            </p>
            <EnglishDateTime
              name="preferredTime"
              label="Preferred visit time"
            />
            <div className="maintenance-photo-picker">
              <p>Photos (optional, up to 3)</p>
              <input
                ref={photoInput}
                type="file"
                accept="image/jpeg,image/png"
                multiple
                hidden
                disabled={busy || photos.length >= 3}
                onChange={addPhotos}
              />
              <div className="actions">
                <button
                  type="button"
                  disabled={busy || photos.length >= 3}
                  onClick={() => photoInput.current?.click()}
                >
                  Choose photos
                </button>
                <span role="status">
                  {photos.length === 0
                    ? "No photos selected"
                    : `${photos.length} of 3 photos selected`}
                </span>
              </div>
            </div>
            <p className="hint">JPG or PNG, up to 5 MB per photo.</p>
            <div className="maintenance-photos">
              {photos.map((file, i) => (
                <div key={i}>
                  <img src={previews[i]} alt={`Selected photo ${i + 1}`} />
                  <small>{file.name}</small>
                  <button
                    type="button"
                    disabled={busy}
                    onClick={() =>
                      setPhotos((old) => old.filter((_, index) => index !== i))
                    }
                  >
                    Remove photo {i + 1}
                  </button>
                </div>
              ))}
            </div>
            <p className="hint">
              Preferred time is a request, not a confirmed appointment.
            </p>
            <button className="primary" disabled={busy}>
              Submit request
            </button>
          </form>
        )}
        <label>
          Status
          <select value={filter} onChange={filterBy(setFilter)}>
            <option value="">All statuses</option>
            {Object.entries(labels).map(([key, label]) => (
              <option key={key} value={key}>
                {label}
              </option>
            ))}
          </select>
        </label>
        {manager && (
          <div className="grid">
            <label>
              Priority
              <select
                value={priorityFilter}
                onChange={filterBy(setPriorityFilter)}
              >
                <option value="">All priorities</option>
                <option value="URGENT">Urgent</option>
                <option value="NORMAL">Normal</option>
              </select>
            </label>

            <label>
              Category
              <select
                value={categoryFilter}
                onChange={filterBy(setCategoryFilter)}
              >
                <option value="">All categories</option>
                {categories.map((category) => (
                  <option key={category} value={category}>
                    {category}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Submitted from
              <input
                type="text"
                placeholder="YYYY-MM-DD"
                value={submittedFrom}
                aria-invalid={invalidDateRange || invalidDateInput}
                onChange={filterBy(setSubmittedFrom)}
              />
            </label>

            <label>
              Submitted through
              <input
                type="text"
                placeholder="YYYY-MM-DD"
                value={submittedTo}
                aria-invalid={invalidDateRange || invalidDateInput}
                onChange={filterBy(setSubmittedTo)}
              />
            </label>

            <p className="hint">
              Submission dates use your browser’s local timezone.
            </p>

            {invalidDateInput ? (
              <p className="message error" role="alert">
                Enter a valid date in YYYY-MM-DD format. Date filtering is not applied.
              </p>
            ) : invalidDateRange && (
              <p className="message error" role="alert">
                Start date must be on or before end date. Date filtering is not
                applied.
              </p>
            )}
            <label>
              Assigned to
              <select
                value={assigneeFilter}
                onChange={filterBy(setAssigneeFilter)}
              >
                <option value="">All assignees</option>
                <option value="unassigned">Unassigned</option>
                {assignedWorkers.map((worker) => (
                  <option key={worker.id} value={worker.id}>
                    {worker.name}
                  </option>
                ))}
              </select>
            </label>
          </div>
        )}
        {!ready ? (
          listError ? (
            <div>
              <p>{listError}</p>
              <button
                type="button"
                disabled={busy}
                onClick={reloadList}
              >
                Retry
              </button>
            </div>
          ) : (
            <p>Loading requests…</p>
          )
        ) : !visible.length ? (
          <p className="empty">{listLoading ? "Loading requests…" : "No requests in this view."}</p>
        ) : (
          <div className="table-wrap">
            <table>
              <thead>
                <tr>
                  <th>Request</th>
                  <th>Status</th>
                  <th>Assigned to</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {visible.map((t) => (
                  <tr
                    key={t.id}
                    className={selected?.id === t.id ? "selected-row" : ""}
                  >
                    <td>
                      <strong>
                        {t.category} · {t.location}
                      </strong>
                      <small>
                        {t.residentName} · Room {t.room} · {date(t.createdAt)}
                      </small>
                    </td>
                    <td>
                      <MiniProgress status={t.status} />
                      <span className={`badge maintenance-status-${t.status}`}>{labels[t.status]}</span>
                      {t.priority === "URGENT" && <small>Urgent</small>}
                    </td>
                    <td>{t.assigneeName || "Unassigned"}</td>
                    <td>
                      <button
                        disabled={busy}
                        onClick={() => run(() => choose(t))}
                      >
                        Open
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {ready && listError && (
          <p className="message error" role="alert">
            {listError} <button type="button" onClick={reloadList}>Retry</button>
          </p>
        )}
        {ready && <Pager data={pageData} noun="requests" busy={listLoading} onPage={setPageNo} />}
      </section>
      {selected && (
        <section className="card" ref={detailRef} key={`${selected.id}-${selected.version}`}>
          <div className="section-head">
            <div>
              <span className="eyebrow">REQUEST DETAILS</span>
              <h2>
                {selected.category} · {selected.location}
              </h2>
              <small>{selected.ticketNo}</small>
            </div>
            <button disabled={busy} onClick={() => setSelected(null)}>
              Close details
            </button>
          </div>
          <ProgressTracker ticket={selected} history={history} resident={resident} />
          <p className="maintenance-description">{selected.description}</p>
          <div className="grid">
            <p>
              <strong>Preferred visit</strong>
              <br />
              {date(selected.preferredTime)}
            </p>
            <p>
              <strong>Target completion</strong>
              <br />
              {date(selected.targetCompletionTime)}
            </p>
          </div>
          {selected.imageUrls.length > 0 && (
            <div className="actions">
              {selected.imageUrls.map((url, i) => (
                <a key={i} href={portalUrl(url)} target="_blank" rel="noopener noreferrer">
                  {url.startsWith("/api/maintenance/") ? (
                    <img width="150" src={portalUrl(url)} alt={`Repair photo ${i + 1}`} />
                  ) : (
                    <>Photo {i + 1}</>
                  )}
                </a>
              ))}
            </div>
          )}
          {selected.rejectionReason && (
            <p className="message error">
              Rejection reason: {selected.rejectionReason}
            </p>
          )}
          {selected.result && (
            <p className="maintenance-description">
              <strong>{["UNABLE_TO_FIX", "UNRESOLVED"].includes(selected.status) ? "Why it could not be fixed:" : "Work result:"}</strong> {selected.result}
            </p>
          )}
          {manager &&
            ["PENDING", "ACCEPTED", "IN_PROGRESS", "UNABLE_TO_FIX"].includes(
              selected.status,
            ) && (
              <form
                className="maintenance-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  const data = fields(e);
                  run(() =>
                    action("assign", {
                      ...data,
                      assigneeId: Number(data.assigneeId),
                      targetCompletionTime: data.targetCompletionTime
                        ? new Date(data.targetCompletionTime).toISOString()
                        : null,
                    }),
                  );
                }}
              >
                <h3>
                  {selected.assigneeId ? "Reassign request" : "Accept & assign"}
                </h3>
                <div className="grid">
                  <label>
                    Assigned worker
                    <select
                      name="assigneeId"
                      required
                      defaultValue={selected.assigneeId || ""}
                    >
                      <option value="" disabled>
                        Choose a worker
                      </option>
                      {assignees.map((a) => (
                        <option key={a.id} value={a.id}>
                          {a.name} ·{" "}
                          {a.type === "INTERNAL"
                            ? "Property team"
                            : "Service provider"}
                        </option>
                      ))}
                    </select>
                  </label>
                  <label>
                    Priority
                    <select name="priority" defaultValue={selected.priority}>
                      <option value="NORMAL">Normal</option>
                      <option value="URGENT">Urgent</option>
                    </select>
                  </label>
                </div>
                <EnglishDateTime
                  name="targetCompletionTime"
                  label="Target completion"
                />
                <label>
                  Assignment / reassignment note (optional)
                  <textarea name="note" maxLength={1000} />
                </label>
                <p className="hint">
                  Reassignment returns the request to Assigned. The selected
                  worker must start it again.
                </p>
                <button disabled={busy} className="primary">
                  Save assignment
                </button>
              </form>
            )}
          {manager && selected.status === "UNABLE_TO_FIX" && (
            <form
              className="maintenance-form"
              onSubmit={(e) => {
                e.preventDefault();
                const data = fields(e);
                run(() => action("close", data));
              }}
            >
              <h3>Close as unresolved</h3>
              <p className="hint">Use this when no one can fix the problem. To try again, reassign the request above instead.</p>
              <label>
                Reason shown to the resident
                <textarea name="note" required maxLength={1000} />
              </label>
              <button disabled={busy}>Close as unresolved</button>
            </form>
          )}
          {manager && selected.status === "PENDING" && (
            <form
              className="maintenance-form"
              onSubmit={(e) => {
                e.preventDefault();
                const data = fields(e);
                run(() => action("reject", data));
              }}
            >
              <label>
                Reason for rejection
                <textarea name="note" required maxLength={1000} />
              </label>
              <button disabled={busy}>Reject request</button>
            </form>
          )}
          {worker && selected.status === "ACCEPTED" && (
            <button
              className="primary"
              disabled={busy}
              onClick={() => run(() => action("start"))}
            >
              Start work
            </button>
          )}
          {worker && selected.status === "IN_PROGRESS" && (
            <form
              className="maintenance-form"
              onSubmit={(e) => {
                e.preventDefault();
                const data = fields(e);
                run(() => action("resolve", data));
              }}
            >
              <fieldset className="maintenance-outcome">
                <legend>Outcome</legend>
                <label className="check-label"><input type="radio" name="outcome" value="FIXED" defaultChecked /> Fixed · send to the resident for confirmation</label>
                <label className="check-label"><input type="radio" name="outcome" value="UNABLE_TO_FIX" /> Could not fix · return to property management</label>
              </fieldset>
              <label>
                What was done, or why it could not be fixed
                <textarea name="note" required maxLength={2000} />
              </label>
              <button className="primary" disabled={busy}>
                Submit result
              </button>
            </form>
          )}
          {resident && selected.status === "PENDING_CONFIRMATION" && (
            <div className="maintenance-form">
              <p>Check the repair before confirming completion.</p>
              <button
                disabled={busy}
                className="primary"
                onClick={() => run(() => action("confirm"))}
              >
                Confirm it is fixed
              </button>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const data = fields(e);
                  run(() => action("reopen", data));
                }}
              >
                <label>
                  Still not fixed? Tell the worker what is wrong
                  <textarea name="note" required maxLength={1000} />
                </label>
                <button disabled={busy}>Report still not fixed</button>
              </form>
            </div>
          )}
          <h3>Activity history</h3>
          <ol className="activity-list">
            {history.map((e) => (
              <li key={e.id}>
                <strong>
                  {e.action.replaceAll("_", " ")} · {e.actorName}
                </strong>
                <p>{e.note}</p>
                <small>{date(e.createdAt)}</small>
              </li>
            ))}
          </ol>
        </section>
      )}
    </div>
  );
}
