import { Injectable } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../environments/environment';

export interface Order {
  id: number;
  customerId: number;
  status: string;
  total: number;
}

@Injectable({ providedIn: 'root' })
export class OrderService {
  private readonly baseUrl = `${environment.apiUrl}/orders`;

  constructor(private http: HttpClient) {}

  list(status?: string): Observable<Order[]> {
    return this.http.get<Order[]>(this.baseUrl, { params: status ? { status } : {} });
  }

  get(id: number): Observable<Order> {
    return this.http.get<Order>(`${this.baseUrl}/${id}`);
  }

  save(order: Order): Observable<Order> {
    return this.http.post<Order>(this.baseUrl, order);
  }

  invoices(orderId: number): Observable<unknown[]> {
    return this.http.get<unknown[]>(`${environment.billingUrl}/invoices?order=${orderId}`);
  }

  geocode(address: string): Observable<unknown> {
    return this.http.get('https://geo.acme.local/api/v1/geocode', { params: { address } });
  }
}
