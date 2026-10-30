# frontend (Angular 21)

Folder: `frontend/`. Angular 21 with standalone components, signals and no zone.js
(zoneless change detection); plain CSS; tests with Vitest. In Docker, nginx serves the built
app and passes `/api` to the backend container (`nginx.conf`).

## Run it on your machine

Needs Node.js 22. Start the backend first (Docker task 1, or the backend on port 8080).

```bash
npm ci                       # packages from package-lock.json (never npm install, decision 198)
npx ng serve                 # http://localhost:4200; /api goes to http://localhost:8080 (proxy.conf.json)
npx ng test --watch=false    # unit tests
npx ng build                 # production build in dist/frontend/browser
```

## Files

```
src/
  main.ts                 starts the app with app.config.ts
  index.html, styles.css  page shell and the shared look (colour tokens, light and dark)
  app/
    app.ts                root component: only a router outlet
    app.config.ts         router, HttpClient (with the XSRF header), error listener
    app.routes.ts         the pages and the login guard
    auth.service.ts       who is logged in (signal), login, logout, health
    auth.guard.ts         sends people who are not logged in to /login
    api.service.ts        every /api call with its TypeScript types
    login/                login page
    shell/                top bar (name, role, version, Log out) around every page after login
    projects/             list of projects
    new-project/          create a project with the folder picker
    project/              one project: start, follow and cancel scans, components, members
    inventory/            files of the newest scan with filters and totals per language
    results/              the newest scan's headline numbers, charts, effort, findings, coverage
    facts/                every kind of fact, one kind at a time, with search, filter and paging
```

## Pages (`app.routes.ts`)

| Path | Component | What it shows |
| --- | --- | --- |
| `/login` | `Login` | Test login (name only) and the version |
| `/` | `Projects` | Projects the user can see; **New project** for owners and admins |
| `/new` | `NewProject` | Name, description and the source folder picker (`/api/sources`) |
| `/p/:id` | `ProjectPage` | Start scan / full rescan, live status (asks every 1.5 s), log, cancel, past scans, components, who has access |
| `/p/:id/inventory` | `Inventory` | Lines of code per language; files filtered by language, category, component, text |
| `/p/:id/results` | `Results` | Headline numbers (each links to its facts), effort, bar charts (languages, complexity, PostgreSQL grades, severities), top findings, vulnerability sources, inputs provided, warnings |
| `/p/:id/facts/:kind` | `Facts` | One kind of fact (`KINDS` in `api.service.ts`): text search, one field filter (`~` for contains), 50 rows a page; for batch jobs also the batches with no schedule |

Every path except `/login` sits inside `Shell` and is protected by `loggedIn`.

## How a page is built

- Each page is a standalone component with its template in a `.html` file next to it.
- State is held in signals (`signal`, `computed`); templates use the built-in control flow
  (`@if`, `@for`).
- Data comes only from `ApiService`; errors are shown with `problem(err)`, which returns the
  backend's plain `detail` message.
- What the user may do comes from the role the backend returns on the project (for example
  `canScan` in `ProjectPage`); the backend checks again on every call.
- Styles: shared classes and colour tokens (`--bg`, `--panel`, `--text`, `--accent` ...) are in
  `styles.css`, with a dark set under `prefers-color-scheme: dark`. Pages add little CSS of their
  own.

## Adding a page

1. Add the backend endpoint first (see [audit-server.md](audit-server.md)), then its method and
   types in `api.service.ts`.
2. Create `src/app/<page>/<page>.ts` and `.html` as a standalone component, with a short TSDoc
   comment on the class saying what the page is for.
3. Add the route in `app.routes.ts` under the `Shell` children, with a `title`.
4. Add a `<page>.spec.ts` test (see `projects.spec.ts`: the `HttpTestingController` answers the
   API calls).
5. Keep the words on screen short and plain: the users are mostly not developers.

## Generated reference

A Compodoc site of the Angular classes is added with the remaining screens (Phase 8).
