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

export interface EnrichStage {
  number: number;
  name: string;
  title: string;
  file: string;
  state: 'missing' | 'valid' | 'stale' | 'invalid' | 'not validated';
  why: string[];
}

export interface EnrichJob {
  run_id: string;
  label: string;
  user: string;
  state: 'running' | 'done' | 'failed';
  stage: string;
  done: number;
  total: number;
  messages: string[];
  valid: string[];
  invalid: string[];
  error: string | null;
  started_at: string;
  ended_at: string | null;
}

export interface ValidationMessage {
  level: 'error' | 'warning';
  stage: string;
  where: string;
  text: string;
}

export interface Enrichment {
  run_id: string | null;
  stages: EnrichStage[];
  /** The Copilot prompt to paste next, or null when every stage is present and up to date. */
  next_prompt: string | null;
  /** The model set under ai: in audit.yaml; problem says why the site cannot call it. */
  ai: { provider: string; label: string; problem: string | null };
  ai_allowed: boolean;
  job: EnrichJob | null;
  /** Only in the answer to a validation. */
  checked?: string[];
  messages?: ValidationMessage[];
}

/** One run folder of a project (newest first in the list). */
export interface RunRow {
  run_id: string;
  started_at: string | null;
  finished_at: string | null;
  full_scan: boolean | null;
  files: number | null;
  analyzed: number | null;
  cached: number | null;
  loc_code: number | null;
  components: number | null;
  findings: number | null;
  warnings: number;
  latest: boolean;
  seconds: number;
  git: { name: string; ref: string | null; commit: string }[];
}

export interface DiffRecord {
  id: string;
  label: string;
  fields?: Record<string, unknown>;
}

export interface EntityDiff {
  count_a: number;
  count_b: number;
  added: DiffRecord[];
  removed: DiffRecord[];
  changed: DiffRecord[];
  added_count: number;
  removed_count: number;
  changed_count: number;
}

export interface Comparison {
  run_a: string;
  run_b: string;
  entities: Record<string, EntityDiff>;
}

/** A part of a component: the first rows of one kind of fact and how many there are. */
export interface ComponentPart {
  total: number;
  rows: Record<string, unknown>[];
}

export interface ComponentDetail {
  component: Record<string, unknown> & { name: string; type: string; files: number; loc_code: number };
  [kind: string]: unknown;
}

export interface Evidence {
  file: string;
  line: number | null;
  start: number;
  end: number;
  text: string;
}

export interface ConfigView {
  text: string;
  fields: {
    application: { name: string; description: string | null; owner: string | null };
    source: { type: string; paths?: string[]; repos?: { url: string; ref?: string | null; paths?: string[]; token_env?: string | null }[] };
    target: Record<string, string | null>;
    include: string[];
    exclude: string[];
  };
  path: string;
}

export interface InputKind {
  kind: string;
  label: string;
  folder: string;
  files: { name: string; size: number }[];
}

export interface ChoiceRow {
  name: string;
  root: string;
  path: string;
  detected_type: string;
  type_override: string | null;
  scan_type: string | null;
  files: number;
  languages: Record<string, number>;
  detected_by: string;
  build_group: string[];
  packaging: string | null;
  parts: string[];
  folders: string[];
  included: boolean;
}

export interface Choices {
  roots: { name: string; path: string }[];
  files: number;
  components: ChoiceRow[];
  excludes: string[];
  skipped: { path: string; reason: string; files: number }[];
}

export interface ToolCheck {
  name: string;
  status: string;
  required: boolean;
  detail: string;
  needed_for: string;
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

  dashboard(id: string, run = ''): Observable<{ scan: Scan | null; run_id: string | null; dashboard: Dashboard | null }> {
    const params: Record<string, string> = run ? { run } : {};
    return this.http.get<{ scan: Scan | null; run_id: string | null; dashboard: Dashboard | null }>(
      `/api/projects/${encodeURIComponent(id)}/dashboard`, { params });
  }

  facts(id: string, kind: string, query: Record<string, string | number>): Observable<{ scan: Scan | null; run_id: string | null; result: FactPage | null }> {
    let params = new HttpParams();
    for (const [key, value] of Object.entries(query)) {
      if (value !== '') {
        params = params.set(key, String(value));
      }
    }
    return this.http.get<{ scan: Scan | null; run_id: string | null; result: FactPage | null }>(
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

  enrichment(id: string): Observable<Enrichment> {
    return this.http.get<Enrichment>(`/api/projects/${encodeURIComponent(id)}/enrichment`);
  }

  validateEnrichment(id: string): Observable<Enrichment> {
    return this.http.post<Enrichment>(`/api/projects/${encodeURIComponent(id)}/enrichment/validate`, {});
  }

  runEnrichment(id: string, stale: boolean): Observable<Enrichment> {
    return this.http.post<Enrichment>(`/api/projects/${encodeURIComponent(id)}/enrichment/run`, { stale });
  }

  runs(id: string): Observable<RunRow[]> {
    return this.http.get<RunRow[]>(`/api/projects/${encodeURIComponent(id)}/runs`);
  }

  compare(id: string, a: string, b: string): Observable<Comparison> {
    return this.http.get<Comparison>(`/api/projects/${encodeURIComponent(id)}/compare`, { params: { a, b } });
  }

  component(id: string, run: string, name: string): Observable<ComponentDetail> {
    return this.http.get<ComponentDetail>(
      `/api/projects/${encodeURIComponent(id)}/runs/${encodeURIComponent(run)}/components/${encodeURIComponent(name)}`);
  }

  evidence(id: string, run: string, file: string, line: number | null): Observable<Evidence> {
    const params: Record<string, string> = { file };
    if (line) {
      params['line'] = String(line);
    }
    return this.http.get<Evidence>(`/api/projects/${encodeURIComponent(id)}/runs/${encodeURIComponent(run)}/evidence`, { params });
  }

  config(id: string): Observable<ConfigView> {
    return this.http.get<ConfigView>(`/api/projects/${encodeURIComponent(id)}/config`);
  }

  saveConfigFields(id: string, fields: Partial<ConfigView['fields']>): Observable<ConfigView> {
    return this.http.put<ConfigView>(`/api/projects/${encodeURIComponent(id)}/config/fields`, fields);
  }

  saveConfigText(id: string, text: string): Observable<ConfigView> {
    return this.http.put<ConfigView>(`/api/projects/${encodeURIComponent(id)}/config/text`, { text });
  }

  inputs(id: string): Observable<{ inputs_dir: string; kinds: InputKind[] }> {
    return this.http.get<{ inputs_dir: string; kinds: InputKind[] }>(`/api/projects/${encodeURIComponent(id)}/inputs`);
  }

  uploadInput(id: string, kind: string, file: File): Observable<{ saved: string; size: number }> {
    return this.http.put<{ saved: string; size: number }>(
      `/api/projects/${encodeURIComponent(id)}/inputs/${encodeURIComponent(kind)}/${encodeURIComponent(file.name)}`, file,
      { headers: { 'Content-Type': 'application/octet-stream' } });
  }

  deleteInput(id: string, kind: string, name: string): Observable<{ deleted: string }> {
    return this.http.delete<{ deleted: string }>(
      `/api/projects/${encodeURIComponent(id)}/inputs/${encodeURIComponent(kind)}/${encodeURIComponent(name)}`);
  }

  choices(id: string): Observable<Choices> {
    return this.http.get<Choices>(`/api/projects/${encodeURIComponent(id)}/component-choices`);
  }

  saveChoices(id: string, included: Record<string, boolean>, types: Record<string, string | null>): Observable<Choices> {
    return this.http.put<Choices>(`/api/projects/${encodeURIComponent(id)}/component-choices`, { included, types });
  }

  doctor(id: string): Observable<ToolCheck[]> {
    return this.http.get<ToolCheck[]>(`/api/projects/${encodeURIComponent(id)}/doctor`);
  }
}
