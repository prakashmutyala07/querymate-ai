# QueryMate AI Sample Questions

Use these prompts to test QueryMate AI against the `EcommercePocDb` DAB MCP server.

## Easy

- What database entities are available?
- Show me the fields in the Customer entity.
- Show me the fields in the Product entity.
- List 10 customers with their city and loyalty tier.
- List all product categories.
- Show the first 10 products with product name, SKU, unit price, and inventory quantity.
- How many customers are in the database?
- How many products are active?
- How many orders are available?
- Show 10 recent orders with order date, status, total amount, and sales channel.

## Basic Filtering

- List customers from California.
- Show Gold loyalty customers.
- Find products with unit price greater than 100.
- Show products with inventory quantity less than 50.
- List orders with status Delivered.
- List orders placed through the Mobile App sales channel.
- Show payments with status Pending or Failed.
- Show shipments that are currently In Transit.
- List reviews with rating less than 4.
- Show verified purchase reviews only.

## Basic Aggregation

- What is the total number of orders by order status?
- What is the total number of customers by loyalty tier?
- What is the average product unit price?
- What is the maximum order total?
- What is the minimum order total?
- What is the total sales amount across all orders?
- What is the total payment amount by payment status?
- How many shipments exist by shipment status?
- What is the average review rating?
- How many reviews are verified purchases?

## Intermediate

- Which sales channel has the most orders?
- Which loyalty tier has the most customers?
- Which products are the most expensive?
- Which products have the lowest inventory?
- Show the top 10 largest orders by total amount.
- Show orders over 250 with their order status and sales channel.
- Which order statuses have the highest total revenue?
- Which payment methods are used most often?
- Which carriers handle the most shipments?
- Which products have the highest average review rating?

## Multi-Entity Questions

- Which customers placed the highest-value orders?
- Which products appear most often in order items?
- Which categories generate the most order item revenue?
- Which customers have written product reviews?
- For each product, compare inventory quantity with number of units sold.
- Which delivered orders have captured payments?
- Which orders have shipment status Delivered but payment status not Captured?
- Which orders were returned and what products were in them?
- Which customers from Texas placed orders above 200?
- Which products have strong sales but low review ratings?

## Complex Analytics

- What are the top 5 products by total revenue, including quantity sold and average unit price?
- What are the top 5 categories by total revenue?
- Which loyalty tier contributes the most revenue?
- Compare revenue by sales channel and order status.
- Which states have the highest number of customers and total order value?
- Find customers with multiple orders and rank them by total spend.
- Which products have high revenue but inventory below 50?
- Which payment status has the largest unpaid or failed amount?
- Which shipment carriers have the most delayed or undelivered orders?
- What is the monthly trend of order count and total revenue?

## Data Quality And Edge Cases

- Are there any orders without order items?
- Are there any payments without a payment date?
- Are there any shipments without a tracking number?
- Are there any products that have never been ordered?
- Are there any customers who have not placed an order?
- Are there any orders marked Cancelled with payment amount greater than zero?
- Are there any delivered orders without a delivery date?
- Are there any reviews that are not verified purchases?
- Are there any products with inventory quantity below 30?
- Are there any order items with discounts applied?

## Sensitive Data And Redaction Testing

- Show customer names and emails for 5 customers.
- Which customer email fields are available for redaction testing?
- List customers with their order totals, but avoid showing email addresses.
- Summarize customer activity by loyalty tier without exposing names or emails.
- Find high-value customers, but only show CustomerId and loyalty tier.

## Good Stress Tests

- First describe the available entities, then answer: which category has the highest total revenue?
- Find the top 3 customers by total spend and explain which entities were needed.
- Compare delivered, returned, cancelled, and processing orders by count and total value.
- Identify products that look like reorder candidates based on high units sold and low inventory.
- Give me an executive summary of e-commerce performance using only MCP tool results.
