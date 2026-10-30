import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { EnrichmentPage } from './enrichment';

const stages = [
  { number: 1, name: 'overview', title: 'Orient', file: 'overview.json', state: 'valid', why: [] },
  { number: 2, name: 'classification', title: 'Classify', file: 'classification.json', state: 'stale', why: ['components'] },
  { number: 3, name: 'architecture', title: 'Architecture', file: 'architecture.json', state: 'missing', why: [] },
];
const copilot = {
  run_id: '20261030-010000-abc123',
  stages,
  next_prompt: '/audit-enrich stale',
  ai: { provider: 'copilot', label: 'copilot', problem: null },
  ai_allowed: true,
  job: null,
};

describe('EnrichmentPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EnrichmentPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('shows the stages, the Copilot prompt and the validation messages', async () => {
    const fixture = TestBed.createComponent(EnrichmentPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'owner', members: {} });
    http.expectOne('/api/projects/billing/enrichment').flush(copilot);
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('/audit-enrich stale');
    expect(page.textContent).toContain('Next step in Copilot Chat');
    expect(page.querySelectorAll('tbody tr').length).toBe(3);
    expect(page.textContent).toContain('components');
    expect(page.textContent).not.toContain('Run all stages');

    (Array.from(page.querySelectorAll('button')).find((b) => b.textContent?.includes('Validate now')) as HTMLButtonElement).click();
    const post = http.expectOne('/api/projects/billing/enrichment/validate');
    expect(post.request.method).toBe('POST');
    post.flush({ ...copilot, checked: ['overview'], messages: [{ level: 'error', stage: 'overview', where: 'figures[0]', text: 'figure says 12' }] });
    await fixture.whenStable();
    expect(page.textContent).toContain('1 error(s), 1 stage file(s) checked.');
    expect(page.textContent).toContain('figure says 12');
  });

  it('runs the stages with the configured model', async () => {
    const fixture = TestBed.createComponent(EnrichmentPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'contributor', members: {} });
    http.expectOne('/api/projects/billing/enrichment').flush({ ...copilot, ai: { provider: 'openai', label: 'openai:gpt-x', problem: null } });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('Run here with openai:gpt-x');
    expect(page.textContent).toContain('Or in Copilot Chat');
    (page.querySelector('button.primary') as HTMLButtonElement).click();
    const post = http.expectOne('/api/projects/billing/enrichment/run');
    expect(post.request.body).toEqual({ stale: true });
    post.flush({
      ...copilot,
      ai: { provider: 'openai', label: 'openai:gpt-x', problem: null },
      job: { run_id: copilot.run_id, label: 'openai:gpt-x', user: 'ann', state: 'done', stage: '', done: 2, total: 2,
        messages: ['Stage 2 classification: valid after 1 round(s)'], valid: ['classification', 'architecture'], invalid: [],
        error: null, started_at: '', ended_at: 'now' },
    });
    await fixture.whenStable();
    expect(page.textContent).toContain('2 stage(s) valid, 0 with errors');
  });

  it('shows viewers the state without buttons', async () => {
    const fixture = TestBed.createComponent(EnrichmentPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'viewer', members: {} });
    http.expectOne('/api/projects/billing/enrichment').flush({ ...copilot, ai: { provider: 'openai', label: 'openai:gpt-x', problem: null } });
    await fixture.whenStable();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.querySelectorAll('button').length).toBe(1); // only Copy
  });
});
