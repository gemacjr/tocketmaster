import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import HomePage from './pages/HomePage.jsx';
import EventPage from './pages/EventPage.jsx';
import CheckoutPage from './pages/CheckoutPage.jsx';
import ConfirmationPage from './pages/ConfirmationPage.jsx';
import './styles.css';

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <header className="topbar">
        <a className="brand" href="/">TOCKET<span>MASTER</span></a>
        <span className="tagline">can't stop. won't stop. ticketing.</span>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/events/:eventId" element={<EventPage />} />
          <Route path="/checkout/:bookingId" element={<CheckoutPage />} />
          <Route path="/booked/:bookingId" element={<ConfirmationPage />} />
        </Routes>
      </main>
    </BrowserRouter>
  </React.StrictMode>
);
