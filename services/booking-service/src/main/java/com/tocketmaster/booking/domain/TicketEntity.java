package com.tocketmaster.booking.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "tickets")
public class TicketEntity {

    @Id
    private UUID id;

    @Column(name = "event_id", insertable = false, updatable = false)
    private UUID eventId;

    private String section;

    @Column(name = "seat_row")
    private String seatRow;

    @Column(name = "seat_num")
    private int seatNum;

    @Column(name = "price_cents")
    private int priceCents;

    /** AVAILABLE | SOLD — reservation-in-progress state lives in Redis holds. */
    private String status;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "version")
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getEventId() { return eventId; }
    public String getSection() { return section; }
    public String getSeatRow() { return seatRow; }
    public int getSeatNum() { return seatNum; }
    public int getPriceCents() { return priceCents; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getBookingId() { return bookingId; }
    public long getVersion() { return version; }
}
