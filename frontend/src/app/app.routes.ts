import { Routes } from '@angular/router';

import { loggedIn } from './auth.guard';
import { Home } from './home/home';
import { Login } from './login/login';

export const routes: Routes = [
  { path: 'login', component: Login, title: 'legacy-audit: log in' },
  { path: '', component: Home, canActivate: [loggedIn], title: 'legacy-audit' },
  { path: '**', redirectTo: '' },
];
