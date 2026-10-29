# legacy-audit frontend (Angular 21)

The screens of the Spring Boot version. In Docker, nginx serves the built app and passes `/api`
to the backend (`nginx.conf`, `docker-compose.spring.yml`).

For development on your machine (Node.js 22):

```bash
npm ci                       # install the packages from package-lock.json
npx ng serve                 # http://localhost:4200, /api goes to the backend on port 8080
npx ng test --watch=false    # unit tests (Vitest)
npx ng build                 # production build in dist/frontend/browser
```

Use `npm ci`, not `npm install`: see decision 198 in `docs/decisions.md`.
