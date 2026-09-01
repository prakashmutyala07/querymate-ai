#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

CONFIG="dab-config.json"

dab update Customer \
  --config "$CONFIG" \
  --fields.name "CustomerId,FullName,Email,Phone,City,StateProvince,Country,CreatedDate,LoyaltyTier" \
  --fields.description "Primary key. Unique identifier for each customer,Sensitive customer display name used for personalization and redaction testing,Sensitive customer email address used for contact and redaction testing,Optional customer phone number for service contact,Customer city used for geographic sales analysis,Customer state or province used for regional analysis,Customer country for market reporting,Date the customer account was created,Loyalty tier such as Bronze Silver Gold or Platinum" \
  --fields.primary-key "true,false,false,false,false,false,false,false,false"

dab update Category \
  --config "$CONFIG" \
  --fields.name "CategoryId,CategoryName,Description,IsActive" \
  --fields.description "Primary key. Unique identifier for each category,Human readable merchandising category name,Business description of what products belong in the category,Indicates whether the category is available for active catalog use" \
  --fields.primary-key "true,false,false,false"

dab update Product \
  --config "$CONFIG" \
  --fields.name "ProductId,CategoryId,ProductName,SKU,UnitPrice,Cost,InventoryQuantity,IsActive,CreatedDate" \
  --fields.description "Primary key. Unique identifier for each product,Foreign key to Category.CategoryId showing the merchandising group for the product,Customer facing product name,Stock keeping unit used as the business product code,Current selling price per unit,Internal unit cost used for margin analysis,Units currently available in inventory,Indicates whether the product is currently available for sale,Date the product was added to the catalog" \
  --fields.primary-key "true,false,false,false,false,false,false,false,false"

dab update Order \
  --config "$CONFIG" \
  --fields.name "OrderId,CustomerId,OrderDate,OrderStatus,Subtotal,TaxAmount,ShippingAmount,TotalAmount,SalesChannel" \
  --fields.description "Primary key. Unique identifier for each order,Foreign key to Customer.CustomerId showing who placed the order,Timestamp when the order was placed,Current order lifecycle status such as Delivered Shipped Processing Returned or Cancelled,Sum of order item line totals before tax and shipping,Tax charged for the order,Shipping fee charged for the order,Final order amount including subtotal tax and shipping,Sales channel where the order was placed" \
  --fields.primary-key "true,false,false,false,false,false,false,false,false"

dab update OrderItem \
  --config "$CONFIG" \
  --fields.name "OrderItemId,OrderId,ProductId,Quantity,UnitPrice,DiscountAmount,LineTotal" \
  --fields.description "Primary key. Unique identifier for each order line,Foreign key to Order.OrderId showing which order contains the line,Foreign key to Product.ProductId showing which product was purchased,Number of product units purchased on this line,Selling price per unit captured at order time,Discount applied to this line item,Final amount for this line after quantity and discount" \
  --fields.primary-key "true,false,false,false,false,false,false"

dab update Payment \
  --config "$CONFIG" \
  --fields.name "PaymentId,OrderId,PaymentMethod,PaymentStatus,Amount,PaymentDate,TransactionReference" \
  --fields.description "Primary key. Unique identifier for each payment record,Foreign key to Order.OrderId showing which order the payment belongs to,Payment instrument or method used by the customer,Current payment state such as Captured Pending Failed or Voided,Payment amount associated with the order,Timestamp when payment was captured or processed,External payment transaction reference when available" \
  --fields.primary-key "true,false,false,false,false,false,false"

dab update Shipment \
  --config "$CONFIG" \
  --fields.name "ShipmentId,OrderId,Carrier,TrackingNumber,ShipmentStatus,ShippedDate,DeliveryDate,ShippingCity,ShippingStateProvince" \
  --fields.description "Primary key. Unique identifier for each shipment,Foreign key to Order.OrderId showing which order is being shipped,Carrier responsible for delivery,Carrier tracking number when a shipment has been created,Current shipment state such as Pending In Transit Delivered Returned or Cancelled,Timestamp when the order shipped,Timestamp when the order was delivered when applicable,Destination city for the shipment,Destination state or province for the shipment" \
  --fields.primary-key "true,false,false,false,false,false,false,false,false"

dab update Review \
  --config "$CONFIG" \
  --fields.name "ReviewId,ProductId,CustomerId,Rating,ReviewTitle,ReviewDate,IsVerifiedPurchase" \
  --fields.description "Primary key. Unique identifier for each review,Foreign key to Product.ProductId showing the reviewed product,Foreign key to Customer.CustomerId showing who wrote the review,Numeric product rating from 1 to 5,Short title summarizing the review,Date the review was submitted,Indicates whether the reviewer purchased the product before reviewing" \
  --fields.primary-key "true,false,false,false,false,false,false"
