import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { AdminPage, detail, size } from './admin';

const settings = {
  reports: { enabled: { summary: true, mgmt: true, functional: true, tech: true, pdf: true, datapack: true },
    summary_sections: { overview: true }, owners_may_hide: true },
  paths: { extra_source_roots: [], git_cache_dir: null },
  users: { open_signup: true, signup_role: 'owner', who_can_create: 'owner' },
  ai: { allowed: true },
  limits: { max_scans: 2, keep_runs: 10, scan_timeout_minutes: 240, clone_keep_days: 14, min_free_gb: 5 },
  defaults: {}, locked: [],
};

describe('AdminPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [AdminPage],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('formats sizes and activity details', () => {
    expect(size(512)).toBe('512 bytes');
    expect(size(1536)).toBe('1.5 KB');
    expect(size(null)).toBe('');
    expect(detail({ time: 't', user: 'a', action: 'scan finished', project: 'p', state: 'done', seconds: 3 })).toBe('state=done; seconds=3');
  });

  it('loads the settings and users and adds a user', async () => {
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();
    http.expectOne('/api/admin').flush({
      settings, defaults_yaml: '', env_locked: { 'limits.max_scans': 3 }, reports: { summary: 'Summary' },
      summary_sections: { overview: 'Overview' }, roles: ['admin', 'owner', 'contributor', 'viewer'], host: {},
    });
    http.expectOne('/api/admin/users').flush([
      { name: 'siva', role: 'admin', disabled: false, created_at: null, last_login: null, fixed: true, projects: 1 },
    ]);
    http.expectOne('/api/admin/projects').flush([]);
    http.expectOne((r) => r.url === '/api/admin/activity').flush([]);
    http.expectOne('/api/admin/health').flush({ version: '1', disk: { total: null, used: null, free: null }, projects: [], checks: [],
      queue: { slots: 2, running: 0, waiting: 0 }, source_roots: [] });
    http.expectOne('/api/admin/clones').flush({ cache: '/audits/.legacy-audit/git', total: 2048, free: 1024, keep_days: 14, min_free_gb: 5,
      clones: [{ key: 'git.example.com/team/a@main', url: 'https://git.example.com/team/a.git', ref: 'main', commit: 'aaaaaaaaaaaa',
        last_used: '2026-11-01T10:00:00+00:00', size: 2048, projects: ['billing'] }] });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.componentInstance;
    expect(page.locked('limits.max_scans')).toBe(true);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('siva');
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('https://git.example.com/team/a.git');

    page.deleteClone(page.clones()!.clones[0]);
    const del = http.expectOne('/api/admin/clones/delete');
    expect(del.request.body).toEqual({ key: 'git.example.com/team/a@main' });
    del.flush({ deleted: 'git.example.com/team/a@main' });
    http.expectOne('/api/admin/clones').flush({ cache: '', total: 0, free: null, keep_days: 14, min_free_gb: 5, clones: [] });
    http.expectOne((r) => r.url === '/api/admin/activity').flush([]);

    page.newUser = ' anna ';
    page.newRole = 'viewer';
    page.addUser();
    const req = http.expectOne('/api/admin/users');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ name: 'anna', role: 'viewer', disabled: false });
    req.flush({});
    http.expectOne('/api/admin/users').flush([]);
    http.expectOne((r) => r.url === '/api/admin/activity').flush([]);
  });

  it('shows the vulnerability database and starts an update', async () => {
    const fixture = TestBed.createComponent(AdminPage);
    fixture.detectChanges();
    http.expectOne('/api/admin').flush({
      settings, defaults_yaml: '', env_locked: {}, reports: {}, summary_sections: {}, roles: ['admin'], host: {},
    });
    http.expectOne('/api/admin/users').flush([]);
    http.expectOne('/api/admin/projects').flush([]);
    http.expectOne((r) => r.url === '/api/admin/activity').flush([]);
    const db = { tool: '/usr/local/bin/dependency-check', version: '13.0.0', folder: '/audits/.legacy-audit/vulndb/dependency-check',
      ready: false, bytes: 0, last_success: null, last_attempt: null, last_ok: null, last_message: null, running: false,
      started_at: null, api_key: false, mirror: null, refresh_at: '02:00', output: [] };
    http.expectOne('/api/admin/health').flush({ version: '1', disk: { total: null, used: null, free: null }, projects: [], checks: [],
      queue: { slots: 2, running: 0, waiting: 0 }, source_roots: [], vulndb: db });
    http.expectOne('/api/admin/clones').flush({ cache: '', total: 0, free: null, keep_days: 14, min_free_gb: 5, clones: [] });
    await fixture.whenStable();
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Dependency-Check 13.0.0');
    expect(text).toContain('No database yet');
    expect(text).toContain('Nightly refresh: 02:00');

    const page = fixture.componentInstance;
    page.updateVulnDb();
    const req = http.expectOne('/api/admin/vulndb/update');
    expect(req.request.method).toBe('POST');
    req.flush({ ...db, ready: true, bytes: 1048576, last_success: '2026-11-04T05:00:00Z', last_ok: true });
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Database 1.0 MB');
    page.ngOnDestroy();
  });
});
