import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { LogsPage } from './logs';

describe('LogsPage', () => {
  let http: HttpTestingController;

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LogsPage],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    }).compileComponents();
    http = TestBed.inject(HttpTestingController);
  });

  it('opens the first log and filters it', async () => {
    const fixture = TestBed.createComponent(LogsPage);
    fixture.detectChanges();
    http.expectOne('/api/logs').flush([{ id: 'server/server.log', label: 'Server log', project: null, run: null, size: 1200 }]);
    http.expectOne((r) => r.url === '/api/logs/view' && r.params.get('id') === 'server/server.log')
      .flush({ id: 'server/server.log', total: 2, lines: ['INFO started', 'WARNING slow'] });
    await fixture.whenStable();
    fixture.detectChanges();
    const page = fixture.nativeElement as HTMLElement;
    expect(page.textContent).toContain('WARNING slow');
    expect(page.querySelector('a.btn')?.getAttribute('href')).toBe('/api/logs/download?id=server%2Fserver.log');

    fixture.componentInstance.level = 'warning';
    fixture.componentInstance.show();
    const req = http.expectOne((r) => r.url === '/api/logs/view');
    expect(req.request.params.get('level')).toBe('warning');
    req.flush({ id: 'server/server.log', total: 1, lines: ['WARNING slow'] });
  });

  it('says when there is no log yet', async () => {
    const fixture = TestBed.createComponent(LogsPage);
    fixture.detectChanges();
    http.expectOne('/api/logs').flush([]);
    await fixture.whenStable();
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('No log yet');
  });
});
