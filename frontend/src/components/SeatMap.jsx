import { useMemo } from 'react';
import { money } from '../api.js';

/**
 * Interactive seat map. Seat status comes from the live seat-map endpoint:
 *   AVAILABLE -> clickable
 *   HELD      -> someone else's 10-min hold (gray)
 *   SOLD      -> gone (dark)
 */
export default function SeatMap({ sections, counts, selected, onToggle }) {
  const selectedSet = useMemo(() => new Set(selected), [selected]);
  if (!sections) return <p className="muted center">Loading seat map…</p>;

  const maxRow = Math.max(...sections.map((s) => Math.max(...s.rows.map((r) => r.seats.length))));
  const tierPrice = (sec) => sec.rows[0]?.seats[0]?.priceCents ?? 0;

  return (
    <div className="seatmap-wrap">
      <svg className="stage" viewBox="0 0 400 40" preserveAspectRatio="xMidYMid meet">
        <path d="M 60 35 Q 200 -15 340 35" fill="none" stroke="#3b82f6" strokeWidth="2" opacity="0.6" />
        <text x="200" y="30" textAnchor="middle" fill="#93c5fd" fontSize="12">STAGE</text>
      </svg>

      <div className="legend">
        <span><i className="dot available" /> Available</span>
        <span><i className="dot selected" /> Your selection</span>
        <span><i className="dot held" /> On hold</span>
        <span><i className="dot sold" /> Sold</span>
        {counts && (
          <span className="muted">
            {counts.available} available · {counts.held} held · {counts.sold} sold
          </span>
        )}
      </div>

      {sections.map((section) => (
        <div className="section" key={section.code}>
          <div className="section-head">
            <b>{section.code}</b>
            <span className="muted small">{tierPrice(section) ? money(tierPrice(section)) : ''} · {section.tier}</span>
          </div>
          <div
            className="rows"
            style={{ gridTemplateColumns: `repeat(${maxRow}, minmax(18px, 26px))` }}
          >
            {section.rows.map((row) => (
              <div className="row" key={row.row}>
                {row.seats.map((seat) => {
                  const isSelected = selectedSet.has(seat.ticketId);
                  const cls = isSelected ? 'selected' : seat.status.toLowerCase();
                  const disabled = !isSelected && seat.status !== 'AVAILABLE';
                  return (
                    <button
                      key={seat.ticketId}
                      className={`seat ${cls}`}
                      title={`${section.code} · Row ${row.row} · Seat ${seat.num} · ${money(seat.priceCents)}`}
                      disabled={disabled}
                      onClick={() => onToggle(seat.ticketId)}
                    >
                      {seat.num}
                    </button>
                  );
                })}
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
