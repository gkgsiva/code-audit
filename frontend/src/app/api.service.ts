import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Scan {
  id: number;
  projectId: string;
  status: 'queued' | 'running' | 'done' | 'failed' | 'cancelled';
  fullScan: boolean;
  startedBy: string | null;
  queuedAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  runId: string | null;
  files: number | null;
  analyzed: number | null;
  cached: number | null;
  components: number | null;
  locCode: number | null;
  warnings: number | null;
  error: string | null;
}

export interface Project {
  id: string;
  title: string;
  description: string | null;
  owner: string | null;
  members: Record<string, string>;
  role: 'admin' | 'owner' | 'contributor' | 'viewer';
  sourcePath: string | null;
  createdAt: string;
  lastScan: Scan | null;
  lastDone: string | null;
}

export interface Folder {
  name: string;
  path: string;
}

export interface Folders {
  path: string;
  parent: string | null;
  folders: Folder[];
}

export interface ScanStatus {
  scan: Scan;
  progress: { stage: string; done: number; total: number } | null;
  log: string[];
}

export interface InventoryFile {
  path: string;
  root: string;
  origin: string;
  component: string | null;
  language: string | null;
  category: string | null;
  size: number;
  encoding: string | null;
  locCode: number;
  locComment: number;
  locBlank: number;
}

export interface Totals {
  name: string;
  files: number;
  locCode: number;
  locComment: number;
  locBlank: number;
}

export interface Inventory {
  scan: Scan | null;
  total: number;
  files: InventoryFile[];
  languages: Totals[];
  categories: Totals[];
}

export interface ComponentRow {
  id: string;
  name: string;
  type: string;
  path: string | null;
  technology: string;
  files: number;
  locCode: number;
  detectedBy: string | null;
}

export interface InventoryFilter {
  language?: string;
  category?: string;
  component?: string;
  q?: string;
  page?: number;
  size?: number;
}

export interface Kpi {
  label: string;
  value: number;
  metric: string;
}

export interface Dashboard {
  run_id: string;
  application: string | null;
  started_at: string | null;
  kpis: Kpi[];
  charts: Record<'technology' | 'complexity' | 'pg_grade' | 'severity', Record<string, number>>;
  coverage: Record<string, string>;
  warnings: string[];
  components: { name: string; type: string; files: number; loc_code: number }[];
  top_findings: { rule: string; severity: string; file: string | null; line: number | null; message: string }[];
  effort: { label: string; unit: string; low: number; likely: number; high: number } | null;
  timings: Record<string, number>;
  vulnerability_sources: { name: string; status: string; detail: string | null; database_date: string | null; reports: string[] }[];
  target: Record<string, unknown> | null;
}

export interface FactPage {
  kind: string;
  total: number;
  offset: number;
  returned: number;
  rows: Record<string, unknown>[];
  note?: string;
  unscheduled_batches?: Record<string, unknown>[];
}

/** The kinds of facts a run holds, in the order of the facts model. */
export const KINDS = [
  'findings', 'components', 'services', 'screens', 'sql_statements', 'db_objects', 'integrations', 'web_services',
  'batch_jobs', 'libraries', 'tech_stack', 'entry_points', 'dependencies', 'metrics', 'input_coverage', 'inventory',
] as const;

/** The backend's message for a refused call, in plain words. */
export interface ReportJob {
  type: string;
  run_id: string;
  user: string;
  state: 'running' | 'done' | 'failed';
  stage: string;
  done: number;
  total: number;
  messages: string[];
  error: string | null;
  started_at: string;
  ended_at: string | null;
}

export interface Reports {
  scan: Scan | null;
  run_id: string | null;
  /** Report name (tech, functional, mgmt, summary, pdf_*) to its address, or null when not built. */
  reports: Record<string, string | null>;
  datapack: string | null;
  job: ReportJob | null;
}

export function problem(err: HttpErrorResponse): string {
  return err.error?.detail ?? 'The server did not answer. Is it running?';
}

/** Projects, scans, inventory and results calls. */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  projects(): Observable<Project[]> {
    return this.http.get<Project[]>('/api/projects');
  }

  project(id: string): Observable<Project> {
    return this.http.get<Project>(`/api/projects/${encodeURIComponent(id)}`);
  }

  createProject(body: { title: string; id?: string; description?: string; sourcePath: string }): Observable<Project> {
    return this.http.post<Project>('/api/projects', body);
  }

  setMembers(id: string, members: Record<string, string>): Observable<Project> {
    return this.http.put<Project>(`/api/projects/${encodeURIComponent(id)}/members`, { members });
  }

  sources(path = ''): Observable<Folders> {
    return this.http.get<Folders>('/api/sources', { params: { path } });
  }

  startScan(id: string, full: boolean): Observable<ScanStatus> {
    return this.http.post<ScanStatus>(`/api/projects/${encodeURIComponent(id)}/scans`, { full });
  }

  scans(id: string): Observable<Scan[]> {
    return this.http.get<Scan[]>(`/api/projects/${encodeURIComponent(id)}/scans`);
  }

  scan(id: string, scanId: number): Observable<ScanStatus> {
    return this.http.get<ScanStatus>(`/api/projects/${encodeURIComponent(id)}/scans/${scanId}`);
  }

  cancelScan(id: string, scanId: number): Observable<ScanStatus> {
    return this.http.post<ScanStatus>(`/api/projects/${encodeURIComponent(id)}/scans/${scanId}/cancel`, {});
  }

  inventory(id: string, filter: InventoryFilter): Observable<Inventory> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(filter)) {
      if (value !== undefined && value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<Inventory>(`/api/projects/${encodeURIComponent(id)}/inventory`, { params });
  }

  dashboard(id: string): Observable<{ scan: Scan | null; dashboard: Dashboard | null }> {
    return this.http.get<{ scan: Scan | null; dashboard: Dashboard | null }>(`/api/projects/${encodeURIComponent(id)}/dashboard`);
  }

  facts(id: string, kind: string, query: Record<string, string | number>): Observable<{ scan: Scan | null; result: FactPage | null }> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<{ scan: Scan | null; result: FactPage | null }>(
      `/api/projects/${encodeURIComponent(id)}/facts/${encodeURIComponent(kind)}`, { params });
  }

  components(id: string): Observable<{ scan: Scan | null; components: ComponentRow[] }> {
    return this.http.get<{ scan: Scan | null; components: ComponentRow[] }>(`/api/projects/${encodeURIComponent(id)}/components`);
  }

  reports(id: string): Observable<Reports> {
    return this.http.get<Reports>(`/api/projects/${encodeURIComponent(id)}/reports`);
  }

  buildReports(id: string, type: string): Observable<Reports> {
    return this.http.post<Reports>(`/api/projects/${encodeURIComponent(id)}/reports`, { type });
  }
}
