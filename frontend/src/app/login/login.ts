import { HttpErrorResponse } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';

import { AuthService } from '../auth.service';

/** Login page: a user name only (test login), as in the Python hosted site. */
@Component({
  selector: 'app-login',
  imports: [FormsModule],
  templateUrl: './login.html',
  styleUrl: './login.css',
})
export class Login implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);

  name = '';
  readonly banner = signal<string | null>(null);
  readonly error = signal<string | null>(null);
  readonly busy = signal(false);
  readonly version = signal('');

  ngOnInit(): void {
    this.auth.info().subscribe({ next: (i) => this.banner.set(i.banner) });
    this.auth.health().subscribe({ next: (h) => this.version.set(h.version) });
  }

  submit(): void {
    this.error.set(null);
    this.busy.set(true);
    this.auth.login(this.name).subscribe({
      next: () => this.router.navigateByUrl('/'),
      error: (err: HttpErrorResponse) => {
        this.busy.set(false);
        this.error.set(err.error?.detail ?? 'The server did not answer. Is it running?');
      },
    });
  }
}
