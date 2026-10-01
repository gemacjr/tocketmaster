import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, fmtDate } from '../api.js';

const CATEGORIES = ['ALL', 'CONCERT', 'SPORTS', 'THEATER', 'COMEDY'];

export default function HomePage() {
  const [q, setQ] = useState('');
  const [category, setCategory] = useState('ALL');
  const [events, setEvents] = useState(null);
  const [meta, setMeta] = useState(null);
  const [error, setError] = useState(null);

  async function load(searchQ, cat) {
    setError(null);
    try {
      if (searchQ || cat !== 'ALL') {
        const res = await api.get(
          `/api/search?q=${encodeURIComponent(searchQ)}&${cat !== 'ALL' ? `category=${cat}` : ''}&size=24`
        );
        setEvents(res.hits);
        setMeta(res);
      } else {
        setEvents(await api.get('/api/events?size=50'));
        setMeta(null);
      }
    } catch (e) {
      setError(e.message);
    }
  }

  useEffect(() => { load(q, category); }, []); // eslint-disable-line

  return (
    <div className="container">
      <section className="hero">
        <h1>Never miss a moment</h1>
        <form
          className="searchbar"
          onSubmit={(e) => { e.preventDefault(); load(q, category); }}
        >
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            placeholder="Search artists, teams, venues…"
          />
          <button type="submit">Search</button>
        </form>
        <div className="chips">
          {CATEGORIES.map((c) => (
            <button
              key={c}
              className={`chip ${category === c ? 'active' : ''}`}
              onClick={() => { setCategory(c); load(q, c); }}
            >
              {c}
            </button>
          ))}
        </div>
      </section>

      {error && <div className="alert error">{error}</div>}
      {meta && (
        <p className="meta">
          {events?.length ?? 0} results · source: <b>{meta.source}</b> · took {meta.tookMs}ms
        </p>
      )}

      <section className="grid">
        {(events || []).map((e) => (
          <Link to={`/events/${e.eventId || e.id}`} className="card" key={e.eventId || e.id}>
            <div className={`card-art ${e.category?.toLowerCase() || ''}`}>
              {(e.performerName || e.name || '?').slice(0, 2).toUpperCase()}
            </div>
            <div className="card-body">
              <h3>{e.name}</h3>
              <p className="muted">
                {e.performerName ? `${e.performerName} · ` : ''}
                {e.venueName ? `${e.venueName} · ` : ''}
                {e.city || ''}
              </p>
              <p className="date">{fmtDate(e.startsAt)}</p>
              <span className="pill">{e.category}</span>
              {e.queueEnabled && <span className="pill hot">WAITING ROOM</span>}
            </div>
          </Link>
        ))}
      </section>

      {!events && !error && <p className="muted center">Loading events…</p>}
    </div>
  );
}
