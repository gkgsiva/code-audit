import axios from 'axios';
import { Repository } from 'typeorm';
import { AppDataSource } from '../db/data-source';
import { pool } from '../db/pool';
import { Order } from '../entities/order.entity';

export class OrderService {
  private readonly orders: Repository<Order> = AppDataSource.getRepository(Order);

  async get(id: number): Promise<Order | null> {
    return this.orders.findOneBy({ id });
  }

  async create(order: Partial<Order>): Promise<Order> {
    const saved = await this.orders.save(order);
    await axios.post('https://payments.acme.local/api/v1/charges', {
      orderId: saved.id,
      amount: saved.total,
    });
    return saved;
  }

  async cancel(id: number): Promise<void> {
    await this.orders.update(id, { status: 'CANCELLED' });
  }

  /** Orders with their customer name, optionally filtered by status. */
  async search(status?: string): Promise<unknown[]> {
    let sql = 'SELECT o.id, o.status, o.total, c.name FROM ORDERS o JOIN CUSTOMERS c ON c.id = o.customer_id';
    const params: string[] = [];
    if (status) {
      sql += ' WHERE o.status = $1';
      params.push(status);
    }
    sql += ' ORDER BY o.id DESC';
    const result = await pool.query(sql, params);
    return result.rows;
  }
}
