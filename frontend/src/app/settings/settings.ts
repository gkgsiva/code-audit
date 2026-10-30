import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';

import { ApiService, ConfigView, Project, ToolCheck, problem } from '../api.service';

/** The version fields each migration target uses ("none" uses none). */
export const TARGET_VERSIONS: Record<string, string[]> = {
  'spring-boot-angular': ['java', 'spring_boot', 'angular', 'database', 'cloud'],
  'wildfly-gke': ['java', 'wildfly', 'jakarta_ee', 'database'],
  none: [],
};
const VERSION_KEYS = ['java', 'spring_boot', 'angular', 'wildfly', 'jakarta_ee', 'database', 'cloud'];

/** One Git repository as a line of the form: url | branch or tag | sub-paths | token variable. */
export function repoLine(r: { url: string; ref?: string | null; paths?: string[]; token_env?: string | null }): string {
  return [r.url, r.ref ?? '', (r.paths ?? []).join(','), r.token_env ?? ''].join(' | ');
}

export function parseRepo(line: string): { url: string; ref?: string; paths?: string[]; token_env?: string } {
  const p = line.split('|').map((s) => s.trim());
  const r: { url: string; ref?: string; paths?: string[]; token_env?: string } = { url: p[0] };
  if (p[1]) {
    r.ref = p[1];
  }
  if (p[2]) {
    r.paths = p[2].split(',').map((s) => s.trim()).filter(Boolean);
  }
  if (p[3]) {
    r.token_env = p[3];
  }
  return r;
}

function lines(text: string): string[] {
  return text.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
}

/** The project's audit.yaml as a form (and as text), plus the tool checks of the server. */
@Component({
  selector: 'app-settings',
  imports: [FormsModule, RouterLink],
  templateUrl: './settings.html',
})
export class SettingsPage implements OnInit {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);

  readonly versionKeys = VERSION_KEYS;
  id = '';
  readonly project = signal<Project | null>(null);
  readonly config = signal<ConfigView | null>(null);
  readonly checks = signal<ToolCheck[] | null>(null);
  readonly error = signal<string | null>(null);
  readonly saved = signal<string | null>(null);
  readonly canEdit = computed(() => ['admin', 'owner'].includes(this.project()?.role ?? ''));

  name = '';
  description = '';
  sourceType = 'local';
  paths = '';
  repos = '';
  profile = 'spring-boot-angular';
  versions: Record<string, string> = {};
  include = '';
  exclude = '';
  text = '';

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.api.project(this.id).subscribe({ next: (p) => this.project.set(p) });
    this.load();
    this.api.doctor(this.id).subscribe({
      next: (c) => this.checks.set(c),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  shown(key: string): boolean {
    return (TARGET_VERSIONS[this.profile] ?? []).includes(key);
  }

  label(key: string): string {
    return key.replace('_', ' ');
  }

  saveFields(): void {
    const f = this.config()?.fields;
    if (!f) {
      return;
    }
    const application = { ...f.application, name: this.name, description: this.description || null };
    let source: ConfigView['fields']['source'] = { type: 'local', paths: lines(this.paths) };
    if (this.sourceType === 'git') {
      const { paths: _paths, ...rest } = f.source.type === 'git' ? f.source : { type: 'git' };
      source = { ...rest, type: 'git', repos: lines(this.repos).map(parseRepo) };
    }
    const target: Record<string, string | null> = { ...f.target, profile: this.profile };
    for (const k of VERSION_KEYS) {
      target[k] = this.versions[k] ?? '';
    }
    this.save(this.api.saveConfigFields(this.id, { application, source, target, include: lines(this.include), exclude: lines(this.exclude) }));
  }

  saveText(): void {
    this.save(this.api.saveConfigText(this.id, this.text));
  }

  statusClass(c: ToolCheck): string {
    return c.status === 'ok' ? 'valid' : c.required ? 'invalid' : 'stale';
  }

  private save(call: ReturnType<ApiService['config']>): void {
    this.error.set(null);
    this.saved.set(null);
    call.subscribe({
      next: (c) => {
        this.fill(c);
        this.saved.set('audit.yaml saved. Start a new scan to use the new settings.');
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private load(): void {
    this.api.config(this.id).subscribe({
      next: (c) => this.fill(c),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  private fill(c: ConfigView): void {
    this.config.set(c);
    const f = c.fields;
    this.name = f.application.name;
    this.description = f.application.description ?? '';
    this.sourceType = f.source.type;
    this.paths = (f.source.paths ?? ['.']).join('\n');
    this.repos = (f.source.repos ?? []).map(repoLine).join('\n');
    this.profile = f.target['profile'] ?? 'spring-boot-angular';
    this.versions = {};
    for (const k of VERSION_KEYS) {
      this.versions[k] = f.target[k] ?? '';
    }
    this.include = (f.include ?? []).join('\n');
    this.exclude = (f.exclude ?? []).join('\n');
    this.text = c.text;
  }
}
