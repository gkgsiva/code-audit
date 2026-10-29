import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, tap } from 'rxjs';

export interface Me {
  name: string;
  role: string;
}

export interface AuthInfo {
  provider: string;
  label: string;
  loginForm: boolean;
  banner: string | null;
}

export interface Health {
  status: string;
  name: string;
  version: string;
  database: string;
}

/** Who is logged in, and the calls that change it. */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);

  /** The logged-in user, or null. */
  readonly me = signal<Me | null>(null);

  info(): Observable<AuthInfo> {
    return this.http.get<AuthInfo>('/api/auth/info');
  }

  health(): Observable<Health> {
    return this.http.get<Health>('/api/health');
  }

  /** Asks the backend who is logged in; false when nobody is. */
  check(): Observable<boolean> {
    return this.http.get<Me>('/api/me').pipe(
      tap((me) => this.me.set(me)),
      map(() => true),
      catchError(() => {
        this.me.set(null);
        return of(false);
      }),
    );
  }

  login(name: string): Observable<Me> {
    return this.http.post<Me>('/api/auth/login', { name }).pipe(tap((me) => this.me.set(me)));
  }

  logout(): Observable<unknown> {
    return this.http.post('/api/auth/logout', {}).pipe(tap(() => this.me.set(null)));
  }
}
