import { useEffect, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router-dom';
import { api, getUserId, money } from '../api.js';
import CountdownTimer from '../components/CountdownTimer.jsx';

const CARDS = [
  { token: 'tok_visa', label: 'Visa ···· 4242' },
  { token: 'tok_amex', label: 'Amex ···· 0005' },
  { token: 'tok_fail', label: 'Declined card (demo)' },
];

export default function CheckoutPage() {
  const { bookingId } = useParams();
  const navigate = useNavigate();
  const locationState = useLocation().state;

  const [booking, setBooking] = useState(locationState || null);
  const [card, setCard] = useState(CARDS[0].token);
  const [paying, setPaying] = useState(false);
  const [error, setError] = useState(null);

  async function loadBooking() {
    try {
      setBooking(await api.get(`/api/bookings/${bookingId}`));
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => { if (!locationState) loadBooking(); }, [bookingId]); // eslint-disable-line

  async function pay() {
    setPaying(true);
    setError(null);
    try {
      const res = await api.post(`/api/bookings/${bookingId}/confirm`, { cardToken: card });
      navigate(`/booked/${res.bookingId}`);
    } catch (e) {
      if (['HOLD_EXPIRED', 'BOOKING_EXPIRED'].includes(e.code)) {
        setError('Your seat hold expired. Please re-select seats.');
        setTimeout(() => navigate(`/events/${booking?.eventId}`), 1800);
      } else {
        setError(`${e.code ?? ''} ${e.message}`.trim());
      }
    } finally {
      setPaying(false);
    }
  }

  async function cancel() {
    try {
      await api.post(`/api/bookings/${bookingId}/cancel`);
    } catch { /* already terminal */ }
    navigate(booking?.eventId ? `/events/${booking.eventId}` : '/');
  }

  if (!booking && !error) return <div className="container"><p className="muted center">Loading…</p></div>;

  return (
    <div className="container narrow">
      <h1>Checkout</h1>
      <p className="muted">
        Buyer: <b>{getUserId()}</b> · Seats held for{' '}
        <CountdownTimer
          expiresAt={booking?.expiresAt}
          onExpire={() => loadBooking()}
        />
      </p>

      <div className="card-panel">
        <h3>Your seats</h3>
        {(booking?.seats || []).map((s, i) => (
          <div className="seatline" key={s.ticketId}>
            <span>{i + 1}. Section {s.section}, Row {s.row}, Seat {s.num}</span>
            <b>{money(s.priceCents)}</b>
          </div>
        ))}
        <div className="seatline total">
          <span>Total</span>
          <b>{money(booking?.totalCents ?? 0)}</b>
        </div>
      </div>

      <div className="card-panel">
        <h3>Payment</h3>
        <p className="muted small">
          Card data is tokenized client-side (Stripe.js in production) — raw card numbers never
          reach our servers.
        </p>
        {CARDS.map((c) => (
          <label key={c.token} className={`radio ${card === c.token ? 'active' : ''}`}>
            <input type="radio" name="card" checked={card === c.token}
              onChange={() => setCard(c.token)} />
            {c.label}
          </label>
        ))}
      </div>

      {error && <div className="alert error">{error}</div>}
      <div className="reserve-bar">
        <button onClick={cancel} disabled={paying}>Release seats</button>
        <button className="primary" onClick={pay} disabled={paying}>
          {paying ? 'Processing…' : `Pay ${money(booking?.totalCents ?? 0)}`}
        </button>
      </div>
    </div>
  );
}
