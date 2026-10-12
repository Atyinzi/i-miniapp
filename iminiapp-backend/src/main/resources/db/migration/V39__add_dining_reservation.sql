-- 餐饮预订模块：门店、餐位、菜品套餐、库存、预订、后厨任务与核销。
INSERT INTO bf_resource(resource_type,name,description,status,attributes)
SELECT 'DINING_ORDER','餐饮预订服务','堂食、自提及外卖统一订单','ACTIVE',JSON_OBJECT('managedBy','dining-module')
WHERE NOT EXISTS(SELECT 1 FROM bf_resource WHERE resource_type='DINING_ORDER');
INSERT INTO bf_resource_sku(resource_id,sku_code,name,price_cent,status,attributes)
SELECT id,'DINING-ORDER-GENERIC','餐饮预订服务',0,'ACTIVE',JSON_OBJECT('priceSource','DINING_SNAPSHOT')
FROM bf_resource WHERE resource_type='DINING_ORDER' AND NOT EXISTS(SELECT 1 FROM bf_resource_sku WHERE sku_code='DINING-ORDER-GENERIC') LIMIT 1;

CREATE TABLE bf_dining_store(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,store_code VARCHAR(50) NOT NULL,name VARCHAR(120) NOT NULL,address VARCHAR(255) NOT NULL,
 contact_phone VARCHAR(32),open_time TIME NOT NULL,close_time TIME NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_by BIGINT,updated_by BIGINT,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dining_store_code(store_code),INDEX idx_dining_store_status(status),CONSTRAINT fk_dining_store_creator FOREIGN KEY(created_by) REFERENCES bf_user(id),
 CONSTRAINT fk_dining_store_updater FOREIGN KEY(updated_by) REFERENCES bf_user(id),CONSTRAINT chk_dining_store_status CHECK(status IN('ACTIVE','INACTIVE')),CONSTRAINT chk_dining_store_time CHECK(open_time<close_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='餐饮门店';
CREATE TABLE bf_dining_table(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,store_id BIGINT NOT NULL,table_code VARCHAR(50) NOT NULL,name VARCHAR(120) NOT NULL,table_type VARCHAR(20) NOT NULL,capacity INT NOT NULL,status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dining_table_code(store_id,table_code),INDEX idx_dining_table_store(store_id,status,table_type),CONSTRAINT fk_dining_table_store FOREIGN KEY(store_id) REFERENCES bf_dining_store(id),
 CONSTRAINT chk_dining_table_type CHECK(table_type IN('TABLE','PRIVATE_ROOM')),CONSTRAINT chk_dining_table_capacity CHECK(capacity BETWEEN 1 AND 1000),CONSTRAINT chk_dining_table_status CHECK(status IN('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='餐位与雅间';
CREATE TABLE bf_dining_category(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,store_id BIGINT NOT NULL,category_code VARCHAR(50) NOT NULL,name VARCHAR(100) NOT NULL,sort_order INT NOT NULL DEFAULT 0,status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dining_category_code(store_id,category_code),CONSTRAINT fk_dining_category_store FOREIGN KEY(store_id) REFERENCES bf_dining_store(id),CONSTRAINT chk_dining_category_status CHECK(status IN('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品分类';
CREATE TABLE bf_dining_dish(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,store_id BIGINT NOT NULL,category_id BIGINT NOT NULL,dish_code VARCHAR(50) NOT NULL,name VARCHAR(120) NOT NULL,price_cent BIGINT NOT NULL,
 special_price_cent BIGINT,image_url VARCHAR(500),description TEXT,recommended TINYINT(1) NOT NULL DEFAULT 0,special TINYINT(1) NOT NULL DEFAULT 0,status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dining_dish_code(store_id,dish_code),INDEX idx_dining_dish_category(category_id,status),CONSTRAINT fk_dining_dish_store FOREIGN KEY(store_id) REFERENCES bf_dining_store(id),CONSTRAINT fk_dining_dish_category FOREIGN KEY(category_id) REFERENCES bf_dining_category(id),
 CONSTRAINT chk_dining_dish_price CHECK(price_cent>=0 AND (special_price_cent IS NULL OR special_price_cent>=0)),CONSTRAINT chk_dining_dish_status CHECK(status IN('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品';
CREATE TABLE bf_dining_package(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,store_id BIGINT NOT NULL,package_code VARCHAR(50) NOT NULL,name VARCHAR(120) NOT NULL,price_cent BIGINT NOT NULL,image_url VARCHAR(500),description TEXT,recommended TINYINT(1) NOT NULL DEFAULT 0,status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
 created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dining_package_code(store_id,package_code),CONSTRAINT fk_dining_package_store FOREIGN KEY(store_id) REFERENCES bf_dining_store(id),CONSTRAINT chk_dining_package_price CHECK(price_cent>=0),CONSTRAINT chk_dining_package_status CHECK(status IN('ACTIVE','INACTIVE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='餐饮套餐';
CREATE TABLE bf_dining_package_item(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,package_id BIGINT NOT NULL,dish_id BIGINT NOT NULL,quantity INT NOT NULL,
 UNIQUE KEY uk_dining_package_dish(package_id,dish_id),CONSTRAINT fk_dining_package_item_package FOREIGN KEY(package_id) REFERENCES bf_dining_package(id),CONSTRAINT fk_dining_package_item_dish FOREIGN KEY(dish_id) REFERENCES bf_dining_dish(id),CONSTRAINT chk_dining_package_quantity CHECK(quantity>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐菜品';
CREATE TABLE bf_dining_stock(
 dish_id BIGINT PRIMARY KEY,on_hand_quantity INT NOT NULL DEFAULT 0,locked_quantity INT NOT NULL DEFAULT 0,warning_quantity INT NOT NULL DEFAULT 10,version BIGINT NOT NULL DEFAULT 0,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 available_quantity INT AS(on_hand_quantity-locked_quantity) STORED,CONSTRAINT fk_dining_stock_dish FOREIGN KEY(dish_id) REFERENCES bf_dining_dish(id),CONSTRAINT chk_dining_stock CHECK(on_hand_quantity>=0 AND locked_quantity>=0 AND locked_quantity<=on_hand_quantity AND warning_quantity>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品库存';
CREATE TABLE bf_dining_stock_log(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,dish_id BIGINT NOT NULL,change_type VARCHAR(30) NOT NULL,quantity_delta INT NOT NULL,on_hand_after INT NOT NULL,locked_after INT NOT NULL,reference_no VARCHAR(64),idempotency_key VARCHAR(100) NOT NULL,remark VARCHAR(255),created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dining_stock_log_key(idempotency_key),INDEX idx_dining_stock_log(dish_id,created_at),CONSTRAINT fk_dining_stock_log_dish FOREIGN KEY(dish_id) REFERENCES bf_dining_dish(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜品库存流水';
CREATE TABLE bf_dining_reservation(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,reservation_no VARCHAR(32) NOT NULL,user_id BIGINT NOT NULL,client_request_id VARCHAR(64) NOT NULL,store_id BIGINT NOT NULL,table_id BIGINT,order_id BIGINT,
 fulfillment_type VARCHAR(20) NOT NULL,reservation_date DATE NOT NULL,reservation_time TIME NOT NULL,dining_minutes INT NOT NULL DEFAULT 120,guest_count INT NOT NULL,contact_name VARCHAR(64) NOT NULL,contact_mobile VARCHAR(32) NOT NULL,delivery_address VARCHAR(500),remark VARCHAR(500),
 status VARCHAR(24) NOT NULL DEFAULT 'PENDING_PAYMENT',total_amount_cent BIGINT NOT NULL,verification_code_hash CHAR(64),verified_by BIGINT,verified_at DATETIME,cancelled_at DATETIME,completed_at DATETIME,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dining_reservation_no(reservation_no),UNIQUE KEY uk_dining_reservation_request(user_id,client_request_id),UNIQUE KEY uk_dining_reservation_order(order_id),INDEX idx_dining_reservation_slot(table_id,reservation_date,reservation_time,status),INDEX idx_dining_reservation_store_date(store_id,reservation_date,status),
 CONSTRAINT fk_dining_reservation_user FOREIGN KEY(user_id) REFERENCES bf_user(id),CONSTRAINT fk_dining_reservation_store FOREIGN KEY(store_id) REFERENCES bf_dining_store(id),CONSTRAINT fk_dining_reservation_table FOREIGN KEY(table_id) REFERENCES bf_dining_table(id),CONSTRAINT fk_dining_reservation_order FOREIGN KEY(order_id) REFERENCES bf_order(id),CONSTRAINT fk_dining_reservation_verifier FOREIGN KEY(verified_by) REFERENCES bf_user(id),
 CONSTRAINT chk_dining_fulfillment CHECK(fulfillment_type IN('DINE_IN','PICKUP','DELIVERY')),CONSTRAINT chk_dining_reservation_status CHECK(status IN('PENDING_PAYMENT','PAID','PREPARING','READY','VERIFIED','COMPLETED','CANCELLED','REFUND_PENDING','REFUNDING','REFUNDED')),CONSTRAINT chk_dining_reservation_amount CHECK(total_amount_cent>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='餐饮预订';
CREATE TABLE bf_dining_reservation_item(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,reservation_id BIGINT NOT NULL,item_type VARCHAR(20) NOT NULL,dish_id BIGINT,package_id BIGINT,item_code VARCHAR(50) NOT NULL,item_name VARCHAR(120) NOT NULL,quantity INT NOT NULL,unit_price_cent BIGINT NOT NULL,amount_cent BIGINT NOT NULL,
 INDEX idx_dining_reservation_item(reservation_id),CONSTRAINT fk_dining_item_reservation FOREIGN KEY(reservation_id) REFERENCES bf_dining_reservation(id),CONSTRAINT fk_dining_item_dish FOREIGN KEY(dish_id) REFERENCES bf_dining_dish(id),CONSTRAINT fk_dining_item_package FOREIGN KEY(package_id) REFERENCES bf_dining_package(id),CONSTRAINT chk_dining_item_type CHECK(item_type IN('DISH','PACKAGE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='餐饮预订明细';
CREATE TABLE bf_dining_kitchen_ticket(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,reservation_id BIGINT NOT NULL,station VARCHAR(20) NOT NULL DEFAULT 'KITCHEN',status VARCHAR(20) NOT NULL DEFAULT 'NEW',pushed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,accepted_at DATETIME,ready_at DATETIME,completed_at DATETIME,updated_by BIGINT,
 UNIQUE KEY uk_dining_kitchen_reservation(reservation_id,station),INDEX idx_dining_kitchen_status(status,pushed_at),CONSTRAINT fk_dining_kitchen_reservation FOREIGN KEY(reservation_id) REFERENCES bf_dining_reservation(id),CONSTRAINT fk_dining_kitchen_user FOREIGN KEY(updated_by) REFERENCES bf_user(id),CONSTRAINT chk_dining_kitchen_station CHECK(station IN('KITCHEN','FRONT_DESK')),CONSTRAINT chk_dining_kitchen_status CHECK(status IN('NEW','ACCEPTED','READY','COMPLETED','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='后厨与前台任务';
CREATE TABLE bf_dining_status_log(
 id BIGINT PRIMARY KEY AUTO_INCREMENT,reservation_id BIGINT NOT NULL,from_status VARCHAR(24),to_status VARCHAR(24) NOT NULL,operator_id BIGINT,note VARCHAR(255),idempotency_key VARCHAR(100) NOT NULL,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE KEY uk_dining_status_key(idempotency_key),INDEX idx_dining_status_log(reservation_id,created_at),CONSTRAINT fk_dining_status_reservation FOREIGN KEY(reservation_id) REFERENCES bf_dining_reservation(id),CONSTRAINT fk_dining_status_operator FOREIGN KEY(operator_id) REFERENCES bf_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='餐饮状态流水';
