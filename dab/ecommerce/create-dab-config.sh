#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

CONFIG="dab-config.json"

dab init \
  --config "$CONFIG" \
  --database-type mssql \
  --connection-string "@env('ECOM_MSSQL_CONNECTION_STRING')" \
  --set-session-context false \
  --host-mode production \
  --auth.provider Unauthenticated \
  --rest.enabled false \
  --rest.path /api \
  --rest.request-body-strict true \
  --graphql.enabled false \
  --graphql.path /graphql \
  --mcp.enabled true \
  --mcp.path /mcp \
  --mcp.aggregate-records.query-timeout 30

dab add Customer \
  --config "$CONFIG" \
  --source dbo.Customer \
  --source.type table \
  --permissions "anonymous:read" \
  --rest false \
  --graphql false \
  --mcp.dml-tools true \
  --description "Customer represents people who place orders in the e-commerce store and includes sensitive identity contact data for later redaction testing." \
  --fields.name "CustomerId,FullName,Email,Phone,City,StateProvince,Country,CreatedDate,LoyaltyTier" \
  --fields.description "Primary key. Unique identifier for each customer,Sensitive customer display name used for personalization and redaction testing,Sensitive customer email address used for contact and redaction testing,Optional customer phone number for service contact,Customer city used for geographic sales analysis,Customer state or province used for regional analysis,Customer country for market reporting,Date the customer account was created,Loyalty tier such as Bronze Silver Gold or Platinum" \
  --fields.primary-key "true,false,false,false,false,false,false,false,false"

dab add Category \
  --config "$CONFIG" \
  --source dbo.Category \
  --source.type table \
  --permissions "anonymous:read" \
  --rest false \
  --graphql false \
  --mcp.dml-tools true \
  --description "Category represents a merchandising group used to organize products for browsing and sales analysis." \
  --fields.name "CategoryId,CategoryName,Description,IsActive" \
  --fields.description "Primary key. Unique identifier for each category,Human readable merchandising category name,Business description of what products belong in the category,Indicates whether the category is available for active catalog use" \
  --fields.primary-key "true,false,false,false"

dab add Product \
  --config "$CONFIG" \
  --source dbo.Product \
  --source.type table \
  --permissions "anonymous:read" \
  --rest false \
  --graphql false \
  --mcp.dml-tools true \
  --description "Product represents sellable catalog items linked to categories with price cost inventory and availability details." \
  --fields.name "ProductId,CategoryId,ProductName,SKU,UnitPrice,Cost,InventoryQuantity,IsActive,CreatedDate" \
  --fields.description "Primary key. Unique identifier for each product,Foreign key to Category.CategoryId showing the merchandising group for the product,Customer facing product name,Stock keeping unit used as the business product code,Current selling price per unit,Internal unit cost used for margin analysis,Units currently available in inventory,Indicates whether the product is currently available for sale,Date the product was added to the catalog" \
  --fields.primary-key "true,false,false,false,false,false,false,false,false"

dab add Order \
  --config "$CONFIG" \
  --source "dbo.[Order]" \
  --source.type table \
  --permissions "anonymous:read" \
  --rest false \
  --graphql false \
  --mcp.dml-tools true \
  --description "Order represents a customer purchase transaction with status channel and monetary totals." \
  --fields.name "OrderId,CustomerId,OrderDate,OrderStatus,Subtotal,TaxAmount,ShippingAmount,TotalAmount,SalesChannel" \
  --fields.description "Primary key. Unique identifier for each order,Foreign key to Customer.CustomerId showing who placed the order,Timestamp when the order was placed,Current order lifecycle status such as Delivered Shipped Processing Returned or Cancelled,Sum of order item line totals before tax and shipping,Tax charged for the order,Shipping fee charged for the order,Final order amount including subtotal tax and shipping,Sales channel where the order was placed" \
  --fields.primary-key "true,false,false,false,false,false,false,false,false"

dab add OrderItem \
  --config "$CONFIG" \
  --source dbo.OrderItem \
  --source.type table \
  --permissions "anonymous:read" \
  --rest false \
  --graphql false \
  --mcp.dml-tools true \
  --description "OrderItem represents individual line items within an order linking products to orders with quantity and price as the join table between Order and Product." \
  --fields.name "OrderItemId,OrderId,ProductId,Quantity,UnitPrice,DiscountAmount,LineTotal" \
  --fields.description "Primary key. Unique identifier for each order line,Foreign key to Order.OrderId showing which order contains the line,Foreign key to Product.ProductId showing which product was purchased,Number of product units purchased on this line,Selling price per unit captured at order time,Discount applied to this line item,Final amount for this line after quantity and discount" \
  --fields.primary-key "true,false,false,false,false,false,false"

dab add Payment \
  --config "$CONFIG" \
  --source dbo.Payment \
  --source.type table \
  --permissions "anonymous:read" \
  --rest false \
  --graphql false \
  --mcp.dml-tools true \
  --description "Payment represents payment attempts and captured amounts for customer orders." \
  --fields.name "PaymentId,OrderId,PaymentMethod,PaymentStatus,Amount,PaymentDate,TransactionReference" \
  --fields.description "Primary key. Unique identifier for each payment record,Foreign key to Order.OrderId showing which order the payment belongs to,Payment instrument or method used by the customer,Current payment state such as Captured Pending Failed or Voided,Payment amount associated with the order,Timestamp when payment was captured or processed,External payment transaction reference when available" \
  --fields.primary-key "true,false,false,false,false,false,false"

dab add Shipment \
  --config "$CONFIG" \
  --source dbo.Shipment \
  --source.type table \
  --permissions "anonymous:read" \
  --rest false \
  --graphql false \
  --mcp.dml-tools true \
  --description "Shipment represents fulfillment and delivery tracking details for an order." \
  --fields.name "ShipmentId,OrderId,Carrier,TrackingNumber,ShipmentStatus,ShippedDate,DeliveryDate,ShippingCity,ShippingStateProvince" \
  --fields.description "Primary key. Unique identifier for each shipment,Foreign key to Order.OrderId showing which order is being shipped,Carrier responsible for delivery,Carrier tracking number when a shipment has been created,Current shipment state such as Pending In Transit Delivered Returned or Cancelled,Timestamp when the order shipped,Timestamp when the order was delivered when applicable,Destination city for the shipment,Destination state or province for the shipment" \
  --fields.primary-key "true,false,false,false,false,false,false,false,false"

dab add Review \
  --config "$CONFIG" \
  --source dbo.Review \
  --source.type table \
  --permissions "anonymous:read" \
  --rest false \
  --graphql false \
  --mcp.dml-tools true \
  --description "Review represents customer product ratings used for quality sentiment and verified purchase analysis." \
  --fields.name "ReviewId,ProductId,CustomerId,Rating,ReviewTitle,ReviewDate,IsVerifiedPurchase" \
  --fields.description "Primary key. Unique identifier for each review,Foreign key to Product.ProductId showing the reviewed product,Foreign key to Customer.CustomerId showing who wrote the review,Numeric product rating from 1 to 5,Short title summarizing the review,Date the review was submitted,Indicates whether the reviewer purchased the product before reviewing" \
  --fields.primary-key "true,false,false,false,false,false,false"
