import { Column, Entity, PrimaryGeneratedColumn } from 'typeorm';

@Entity('ORDERS')
export class Order {
  @PrimaryGeneratedColumn()
  id!: number;

  @Column({ name: 'CUSTOMER_ID' })
  customerId!: number;

  @Column()
  status!: string;

  @Column('numeric')
  total!: number;
}
