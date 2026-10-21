import { DataSource } from 'typeorm';
import { Order } from '../entities/order.entity';

export const AppDataSource = new DataSource({
  type: 'postgres',
  host: 'ordersdb.acme.local',
  port: 5432,
  database: 'orders',
  username: process.env.DB_USER,
  password: process.env.DB_PASSWORD,
  entities: [Order],
});
