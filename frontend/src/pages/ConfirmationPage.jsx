import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api, money, fmtDate } from '../api.js';

export default function ConfirmationPage() {
  const { bookingId } = useParams();
  const [booking, setBooking] = useState(null);
  const [error, setError] = useState(null);

  useEffect(() => {
    api.get(`/api/bookings/${bookingId}`).then(setBooking).catch((e) => setError(e.message));
  }, [bookingId]);

  if (error) return <div className="container"><div className="alert error">{error}</div></div>;
  if (!booking) return <div className="container"><p className="muted center">Loading…</p></div>;

  return (
    <div className="container narrow">
      <div className="card-panel confirm">
        <div className="check">✓</div>
        <h1>{booking.status === 'CONFIRMED' ? "You're going!" : `Booking ${booking.status}`}</h1>
        <p className="muted">Booking ID: <code>{booking.bookingId}</code></p>

        {(booking.seats || []).map((s, i) => (
          <div className="seatline" key={s.ticketId}>
            <span>{i + 1}. Section {s.section}, Row {s.row}, Seat {s.num}</span>
            <b>{money(s.priceCents)}</b>
          </div>
        ))}
        <div className="seatline total">
          <span>Total paid</span>
          <b>{money(booking.totalCents)}</b>
        </div>
        <p className="muted small">{fmtDate(booking.expiresAt)}</p>

        <Link to="/">
          <button className="primary">Find your next event</button>
        </Link>
      </div>
    </div>
  );
}
