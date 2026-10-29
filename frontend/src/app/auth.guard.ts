import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { map } from 'rxjs';

import { AuthService } from './auth.service';

/** Sends people who are not logged in to the login page. */
export const loggedIn: CanActivateFn = () => {
  const auth = inject(AuthService);
  const router = inject(Router);
  return auth.check().pipe(map((ok) => ok || router.createUrlTree(['/login'])));
};
