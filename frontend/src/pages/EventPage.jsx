import { useEffect, useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, getSessionId, money, postWithSession, fmtDate } from '../api.js';
import SeatMap from '../components/SeatMap.jsx';

/**
 * Event page: static detail (cached aggressively server-side) + live seat map
 * (polled; SSE/WebSocket is the documented upgrade path). Queue-enabled events
 * route through the virtual waiting room before the seat map unlocks.
 */
export default function EventPage() {
  const { eventId } = useParams();
  const navigate = useNavigate();

  const [detail, setDetail] = useState(null);
  const [seatMap, setSeatMap] = useState(null);
  const [selected, setSelected] = useState([]);
  const [queueState, setQueueState] = useState(null); // {status, position}
  const [error, setError] = useState(null);
  const [reserving, setReserving] = useState(false);

  // load static detail once
  useEffect(() => {
    api.get(`/api/events/${eventId}`).then((d) => {
      setDetail(d);
      if (d.queueEnabled) joinQueue();
    }).catch((e) => setError(e.message));
  }, [eventId]); // eslint-disable-line

  // poll live seat availability
  useEffect(() => {
    let stop = false;
    async function tick() {
      try {
        const s = await api.get(`/api/events/${eventId}/seats`);
        if (!stop) setSeatMap(s);
      } catch { /* transient */ }
    }
    if (canSeeSeats()) {
      tick();
      const t = setInterval(tick, 5000);
      return () => { stop = true; clearInterval(t); };
    }
    return () => { stop = true; };
  }, [eventId, queueState]); // eslint-disable-line

  function canSeeSeats() {
    if (!detail) return false;
    if (!detail.queueEnabled) return true;
    return queueState?.status === 'ADMITTED';
  }

  async function joinQueue() {
    const sessionId = getSessionId(eventId);
    const res = await postWithSession(`/api/queue/events/${eventId}/join`, {}, sessionId);
    setQueueState(res);
  }

  // SSE position updates while waiting
  useEffect(() => {
    if (!detail?.queueEnabled || !queueState || queueState.status === 'ADMITTED') return;
    const es = new EventSource(
      `/api/queue/events/${eventId}/stream?sessionId=${encodeURIComponent(getSessionId(eventId))}`
    );
    es.addEventListener('position', (ev) => {
      const d = JSON.parse(ev.data);
      setQueueState((q) => ({ ...q, status: 'WAITING', position: d.position, queueSize: d.queueSize }));
    });
    es.addEventListener('admitted', () => {
      setQueueState((q) => ({ ...q, status: 'ADMITTED' }));
      es.close();
    });
    es.onerror = () => { /* stream drops are retried by the browser */ };
    return () => es.close();
  }, [detail?.queueEnabled, eventId]); // eslint-disable-line

  const total = useMemo(() => {
    if (!seatMap || selected.length === 0) return 0;
    const priceOf = (id) =>
      seatMap.sections.flatMap((s) => s.rows.flatMap((r) => r.seats))
        .find((x) => x.ticketId === id)?.priceCents ?? 0;
    return selected.reduce((sum, id) => sum + priceOf(id), 0);
  }, [selected, seatMap]);

  function toggle(ticketId) {
    setSelected((sel) =>
      sel.includes(ticketId) ? sel.filter((t) => t !== ticketId)
        : sel.length >= 8 ? sel : [...sel, ticketId]);
  }

  async function reserve() {
    setReserving(true);
    setError(null);
    try {
      const res = await postWithSession('/api/bookings/reservations', {
        eventId,
        ticketIds: selected,
      }, getSessionId(eventId));
      navigate(`/checkout/${res.bookingId}`, { state: res });
    } catch (e) {
      setError(e.body?.details ? `${e.message}: ${JSON.stringify(e.body.details)}` : e.message);
      // refresh the seat map — someone beat us to a seat
      api.get(`/api/events/${eventId}/seats`).then(setSeatMap).catch(() => {});
    } finally {
      setReserving(false);
    }
  }

  if (error && !detail) return <div className="container"><div className="alert error">{error}</div></div>;
  if (!detail) return <div className="container"><p className="muted center">Loading…</p></div>;

  return (
    <div className="container">
      <section className="event-head">
        <div className={`card-art big ${detail.category.toLowerCase()}`}>
          {detail.performer.name.slice(0, 2).toUpperCase()}
        </div>
        <div>
          <h1>{detail.name}</h1>
          <p className="muted">
            {detail.performer.name} · {detail.venue.name}, {detail.venue.city} · {fmtDate(detail.startsAt)}
          </p>
          <span className="pill">{detail.category}</span>
        </div>
      </section>

      {detail.queueEnabled && queueState?.status !== 'ADMITTED' && (
        <WaitingRoom state={queueState || { status: 'WAITING', position: '?' }} onRejoin={joinQueue} />
      )}

      {canSeeSeats() && (
        <>
          {error && <div className="alert error">{error}</div>}
          <SeatMap sections={seatMap?.sections} counts={seatMap?.counts} selected={selected} onToggle={toggle} />
          <div className="reserve-bar">
            <span>
              {selected.length} seat{selected.length === 1 ? '' : 's'} · <b>{money(total)}</b>
            </span>
            <button className="primary" disabled={selected.length === 0 || reserving} onClick={reserve}>
              {reserving ? 'Holding seats…' : 'Reserve seats'}
            </button>
          </div>
          <p className="muted small">Seats are held for 10 minutes once reserved.</p>
        </>
      )}
    </div>
  );
}

function WaitingRoom({ state, onRejoin }) {
  return (
    <div className="waiting-room card-panel">
      <h2>You're in the waiting room</h2>
      <p className="muted">
        Demand is high for this event. We're letting people in gradually so checkout stays fast
        and fair.
      </p>
      <div className="position">{state.position}</div>
      <p>your place in line{state.queueSize ? ` of ${state.queueSize}` : ''}</p>
      <div className="spinner" />
      <button onClick={onRejoin}>Refresh position</button>
    </div>
  );
}
