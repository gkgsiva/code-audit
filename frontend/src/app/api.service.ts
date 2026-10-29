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

/** The backend's message for a refused call, in plain words. */
export function problem(err: HttpErrorResponse): string {
  return err.error?.detail ?? 'The server did not answer. Is it running?';
}

/** Projects, scans and inventory calls. */
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

  components(id: string): Observable<{ scan: Scan | null; components: ComponentRow[] }> {
    return this.http.get<{ scan: Scan | null; components: ComponentRow[] }>(`/api/projects/${encodeURIComponent(id)}/components`);
  }
}
