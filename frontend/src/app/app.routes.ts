import { Routes } from '@angular/router';

import { loggedIn } from './auth.guard';
import { ChoicesPage } from './choices/choices';
import { ComponentPage } from './component/component';
import { EnrichmentPage } from './enrichment/enrichment';
import { EvidencePage } from './evidence/evidence';
import { Facts } from './facts/facts';
import { InputsPage } from './inputs/inputs';
import { Inventory } from './inventory/inventory';
import { Login } from './login/login';
import { NewProject } from './new-project/new-project';
import { ProjectPage } from './project/project';
import { Projects } from './projects/projects';
import { ReportsPage } from './reports/reports';
import { Results } from './results/results';
import { RunsPage } from './runs/runs';
import { SettingsPage } from './settings/settings';
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
      { path: 'p/:id/results', component: Results, title: 'legacy-audit: results' },
      { path: 'p/:id/reports', component: ReportsPage, title: 'legacy-audit: reports' },
      { path: 'p/:id/enrichment', component: EnrichmentPage, title: 'legacy-audit: AI enrichment' },
      { path: 'p/:id/facts/:kind', component: Facts, title: 'legacy-audit: facts' },
      { path: 'p/:id/runs', component: RunsPage, title: 'legacy-audit: runs' },
      { path: 'p/:id/runs/:run/components/:name', component: ComponentPage, title: 'legacy-audit: component' },
      { path: 'p/:id/runs/:run/evidence', component: EvidencePage, title: 'legacy-audit: source' },
      { path: 'p/:id/settings', component: SettingsPage, title: 'legacy-audit: settings' },
      { path: 'p/:id/inputs', component: InputsPage, title: 'legacy-audit: input files' },
      { path: 'p/:id/components', component: ChoicesPage, title: 'legacy-audit: components to scan' },
    ],
  },
  { path: '**', redirectTo: '' },
];
