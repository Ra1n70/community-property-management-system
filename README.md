# Community Property Management System

A web platform that connects residents, property managers and service providers in one community: repair requests, package lockers, amenity bookings, bills, announcements, discussions and private messages, each limited to what that role is allowed to see.

## Features

**Residents** register with a community code and are approved by a manager. They can then:
- submit maintenance requests with photos, follow their progress and confirm the fix;
- pick up packages from the locker kiosk with a 6-digit code or QR code;
- reserve amenities, with opening hours, closures and a number of households per time slot;
- read announcements, vote in community polls and browse local perks (**Community Hub**);
- post and comment on the **Discussion Board**;
- message property management or neighbors, with photo and PDF attachments (**Messages**);
- download lease documents and pay simulated bills (**My Account**).

**Property managers** approve residents and change rooms, assign and track maintenance, manage lockers, couriers, amenities and bills, moderate the discussion board, publish announcements, polls and perks, upload lease documents, keep contact details and FAQ up to date, search across the community's records, and view reports for a date range with CSV export.

**Service providers** are created by a manager:
- **Maintenance** providers work on the tickets assigned to them and report each one as fixed or not fixable.
- **Delivery** companies manage their couriers' personal store codes. Couriers store packages at the kiosk without signing in.

Every request is checked on the server for role, approval status and community.

## Tech Stack

- Frontend: React, Vite, JavaScript, CSS
- Backend: Java 21, Spring Boot, Spring Security (session sign-in with CSRF protection), Spring Data JPA
- Database: PostgreSQL 17 (Docker Compose); H2 for automated tests
- Real-time updates: Server-Sent Events for messages
- QR codes: ZXing

## Requirements

- Java 21
- Maven 3.9+
- Node.js 22.12+ or 24
- Docker Desktop

## Getting Started

Run the commands from the repository root. Each new terminal needs the environment variables set again.

### 1. Start PostgreSQL

Open Docker Desktop, then choose a database password:

Windows (PowerShell):

```powershell
$env:DB_PASSWORD = 'your-database-password'
docker compose up -d db
```

macOS / Linux:

```bash
export DB_PASSWORD='your-database-password'
docker compose up -d db
```

Wait until `docker compose ps` shows the database as `healthy`. Docker creates the `cpms` database and user.

### 2. Start the Backend

In the same terminal, set these values and start the backend:

| Variable | Purpose |
|---|---|
| `DB_PASSWORD` | The database password from step 1 |
| `PICKUP_CODE_SECRET` | A private random string of at least 32 characters. Keep it the same on every restart, or existing pickup and courier codes stop working. |
| `DEMO_MANAGER_PASSWORD` | Password for the manager account created on first startup (10–64 characters) |
| `DEMO_INVITE_CODE` | Community code residents enter when they register |

Windows (PowerShell):

```powershell
$env:PICKUP_CODE_SECRET = 'your-private-secret-of-at-least-32-characters'
$env:DEMO_MANAGER_PASSWORD = 'your-manager-password'
$env:DEMO_INVITE_CODE = 'your-community-code'
cd backend
mvn spring-boot:run
```

macOS / Linux:

```bash
export PICKUP_CODE_SECRET='your-private-secret-of-at-least-32-characters'
export DEMO_MANAGER_PASSWORD='your-manager-password'
export DEMO_INVITE_CODE='your-community-code'
cd backend
mvn spring-boot:run
```

The backend is ready on port 8080 when the log shows `Started CommunityApplication`. On the first startup it creates the manager account `manager@cpms.local` with your `DEMO_MANAGER_PASSWORD`; later startups do not change that password.

### 3. Start the Frontend

In a second terminal:

```bash
cd frontend
npm ci
npm run dev
```

Open the address Vite prints, normally http://127.0.0.1:5173.

## Using the App

- **Sign-in entrances:** Resident (`/resident/login`), Property manager (`/manager/login`) and Service provider (`/provider/login`). Each role has its own session, so different roles can stay signed in in the same browser. Sessions end after 60 minutes without activity.
- **First steps:** sign in as `manager@cpms.local`, then register a resident from the Resident entrance (**Join the community**, using your community code) and approve the resident under **Residents**. Managers create service provider accounts under **Service providers** and share the one-time setup link with the provider.
- **Recovery code:** every account gets a personal recovery code the first time it is needed. Save it; it resets a forgotten password together with the email address. A resident or provider who loses both the password and the code can get a 30-minute, one-use recovery link from their manager.
- **Locker kiosk:** `/locker-panel`. Residents enter a pickup code or scan its QR code. Couriers choose *I'm a courier* and enter their store code. The kiosk is a local demonstration and needs device authentication before being exposed publicly.
- **Uploads:** photos, amenity images, attachments and lease PDFs are stored in `backend/uploads/`, not in the database.

To stop, press Ctrl + C in the backend and frontend terminals and run `docker compose stop db`. Data stays in the Docker volume; `docker compose down -v` deletes it.

## Running Tests

Backend tests use an in-memory H2 database and need neither Docker nor the environment variables:

```bash
cd backend
mvn test
```

Frontend tests and build:

```bash
cd frontend
npm test
npm run build
```

## Team

| Member | GitHub | Role | Modules |
|---|---|---|---|
| Xuanyu Zhang | [@Ra1n70](https://github.com/Ra1n70) | Group Lead · Full stack | Authentication & Roles, Payments, Lease Documents, Contact & Support, Community Voting, Direct Messaging (real-time updates, attachments, frontend), Search (database search), Maintenance workflow, Package courier codes, Management Reports, Discussion Board & Local Perks (frontend), integration |
| Yijin Guo | [@gguoyijin-tech](https://github.com/gguoyijin-tech) | Backend Lead | Discussion Board (backend), Local Perks (backend) |
| Yi He | [@coco161818-spec](https://github.com/coco161818-spec) | Backend | Authentication & Roles (backend) |
| Zhiqiao Kang | [@ZhiqiaoKang](https://github.com/ZhiqiaoKang) | Full stack | Community Announcements |
| Xinye Zhang | [@xinyez166](https://github.com/xinyez166) | Full stack | Package Lockers |
| Huiping Zhou | [@Narkissoz](https://github.com/Narkissoz) | Backend | Amenity Reservations, Management Reports (metrics) |
| Junqing Yang | [@weijunyeyeqiqingfeng](https://github.com/weijunyeyeqiqingfeng) | Frontend | Amenity Reservations (frontend), Maintenance Requests (frontend) |
| Wei Zhang | [@zw-567](https://github.com/zw-567) | Backend | Maintenance Requests (backend) |
| Xunming Zhu | [@sangerzhu](https://github.com/sangerzhu) | Full stack | Search (initial API and page) |
| Zihong Zhu | [@lackname](https://github.com/lackname) | Backend | Direct Messaging, Resident Chat (backend) |
