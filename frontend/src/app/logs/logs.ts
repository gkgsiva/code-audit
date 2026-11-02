import { HttpErrorResponse } from '@angular/common/http';
import { DecimalPipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ApiService, LogFile, LogView, problem } from '../api.service';

/** The server log (admins) and each run's scan log of the user's projects: last lines, filters, download. */
@Component({
  selector: 'app-logs',
  imports: [DecimalPipe, FormsModule],
  templateUrl: './logs.html',
})
export class LogsPage implements OnInit {
  private readonly api = inject(ApiService);

  readonly files = signal<LogFile[]>([]);
  readonly view = signal<LogView | null>(null);
  readonly error = signal<string | null>(null);
  readonly loaded = signal(false);
  id = '';
  level = '';
  q = '';
  tail = 500;

  ngOnInit(): void {
    this.api.logs().subscribe({
      next: (f) => {
        this.files.set(f);
        this.loaded.set(true);
        if (f.length) {
          this.id = f[0].id;
          this.show();
        }
      },
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  show(): void {
    if (!this.id) {
      return;
    }
    this.error.set(null);
    this.api.logView(this.id, this.level, this.q, this.tail).subscribe({
      next: (v) => this.view.set(v),
      error: (err: HttpErrorResponse) => this.error.set(problem(err)),
    });
  }

  download(): string {
    return '/api/logs/download?id=' + encodeURIComponent(this.id);
  }
}
