import { Routes } from '@angular/router';

import { loggedIn } from './auth.guard';
import { Inventory } from './inventory/inventory';
import { Login } from './login/login';
import { NewProject } from './new-project/new-project';
import { ProjectPage } from './project/project';
import { Projects } from './projects/projects';
import { Shell } from './shell/shell';

export const routes: Routes = [
  { path: 'login', component: Login, title: 'legacy-audit: log in' },
  {
    path: '',
    component: Shell,
    canActivate: [loggedIn],
    children: [
      { path: '', component: Projects, title: 'legacy-audit: projects' },
      { path: 'new', component: NewProject, title: 'legacy-audit: new project' },
      { path: 'p/:id', component: ProjectPage, title: 'legacy-audit: project' },
      { path: 'p/:id/inventory', component: Inventory, title: 'legacy-audit: inventory' },
    ],
  },
  { path: '**', redirectTo: '' },
];
