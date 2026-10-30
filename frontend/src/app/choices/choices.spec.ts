import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';

import { ChoicesPage, folderKey } from './choices';

const row = (name: string, folders: string[], included: boolean) => ({
  name, root: 'src', path: folders[0], detected_type: 'web', type_override: null, scan_type: null, files: 4,
  languages: { java: 3, xml: 1 }, detected_by: 'pom.xml', build_group: ['parent'], packaging: 'war', parts: folders.length > 1 ? folders : [],
  folders, included,
});

describe('ChoicesPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ChoicesPage],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: convertToParamMap({ id: 'billing' }) } } },
      ],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('keys a component by its folders', () => {
    expect(folderKey(row('a', ['web', 'web2'], true))).toBe('web|web2');
  });

  it('saves the ticks and type overrides', async () => {
    const fixture = TestBed.createComponent(ChoicesPage);
    fixture.detectChanges();
    http.expectOne('/api/projects/billing').flush({ id: 'billing', role: 'owner' });
    const choices = { roots: [{ name: 'src', path: '/src' }], files: 8, components: [row('orders', ['orders'], true), row('tools', ['tools'], false)],
      excludes: ['tools/'], skipped: [{ path: 'target', reason: 'build output', files: 30 }] };
    http.expectOne('/api/projects/billing/component-choices').flush(choices);
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('8 files in src.');
    expect(page.textContent).toContain('java 3, xml 1');
    expect(page.textContent).toContain('parent · war');
    expect(page.textContent).toContain('target (30 files)');
    expect(page.querySelectorAll('tr.left-out').length).toBe(1);

    const c = fixture.componentInstance;
    c.included['orders'] = false;
    c.overrides['tools'] = 'script';
    c.save();
    const req = http.expectOne('/api/projects/billing/component-choices');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body).toEqual({ included: { orders: false, tools: false }, types: { orders: null, tools: 'script' } });
    req.flush(choices);
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.textContent).toContain('Saved to audit-overrides.yaml');
  });
});
