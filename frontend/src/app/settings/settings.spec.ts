import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { SettingsPage, parseRepo, repoLine } from './settings';

const config = {
  path: 'audit.yaml',
  text: 'application:\n  name: Billing\n',
  fields: {
    application: { name: 'Billing', description: null, owner: null },
    source: { type: 'local', paths: ['src'] },
    target: { profile: 'spring-boot-angular', java: '25', spring_boot: '4.0', angular: '21', database: 'PostgreSQL 18', cloud: 'GKE' },
    include: [],
    exclude: ['**/test/**'],
  },
};

describe('SettingsPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [SettingsPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('reads a repository line and writes it back', () => {
    const r = parseRepo('https://git/x.git | main | a, b | GIT_TOKEN');
    expect(r).toEqual({ url: 'https://git/x.git', ref: 'main', paths: ['a', 'b'], token_env: 'GIT_TOKEN' });
    expect(repoLine(r)).toBe('https://git/x.git | main | a,b | GIT_TOKEN');
    expect(parseRepo('ssh://git/y.git')).toEqual({ url: 'ssh://git/y.git' });
  });

  it('fills the form, saves only the form fields and shows the tool checks', async () => {
    const fixture = TestBed.createComponent(SettingsPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'owner' });
    http.expectOne('/api/projects/billing/config').flush(config);
    http.expectOne('/api/projects/billing/doctor').flush([
      { name: 'Java', status: 'ok', required: true, detail: '25', needed_for: 'everything' },
      { name: 'OSV-Scanner', status: 'missing', required: false, detail: 'not on PATH', needed_for: 'vulnerabilities' },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('OSV-Scanner');
    expect(page.querySelector('.state.stale')?.textContent).toBe('missing');
    expect((page.querySelector('#f-paths') as HTMLTextAreaElement).value).toBe('src');

    const component = fixture.componentInstance;
    component.description = 'The billing system';
    component.paths = 'src\nlib\n';
    component.saveFields();
    const req = http.expectOne('/api/projects/billing/config/fields');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.application.description).toBe('The billing system');
    expect(req.request.body.source).toEqual({ type: 'local', paths: ['src', 'lib'] });
    expect(req.request.body.target.wildfly).toBe('');
    expect(req.request.body.exclude).toEqual(['**/test/**']);
    req.flush(config);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.textContent).toContain('audit.yaml saved');
  });

  it('shows the server message when audit.yaml is not valid', async () => {
    const fixture = TestBed.createComponent(SettingsPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'owner' });
    http.expectOne('/api/projects/billing/config').flush(config);
    http.expectOne('/api/projects/billing/doctor').flush([]);
    fixture.componentInstance.text = 'application: [';
    fixture.componentInstance.saveText();
    http.expectOne('/api/projects/billing/config/text').flush({ detail: 'Not valid YAML: ...' }, { status: 422, statusText: 'Unprocessable' });
    await fixture.whenStable();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Not valid YAML');
  });
});
