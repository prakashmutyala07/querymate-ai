USE master;
GO

IF DB_ID(N'EcommercePocDb') IS NULL
BEGIN
    CREATE DATABASE EcommercePocDb;
END
GO

USE EcommercePocDb;
GO

IF OBJECT_ID(N'dbo.Review', N'U') IS NULL
AND OBJECT_ID(N'dbo.Shipment', N'U') IS NULL
AND OBJECT_ID(N'dbo.Payment', N'U') IS NULL
AND OBJECT_ID(N'dbo.OrderItem', N'U') IS NULL
AND OBJECT_ID(N'dbo.[Order]', N'U') IS NULL
AND OBJECT_ID(N'dbo.Product', N'U') IS NULL
AND OBJECT_ID(N'dbo.Category', N'U') IS NULL
AND OBJECT_ID(N'dbo.Customer', N'U') IS NULL
BEGIN
    CREATE TABLE dbo.Customer (
        CustomerId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Customer PRIMARY KEY,
        FullName NVARCHAR(120) NOT NULL,
        Email NVARCHAR(255) NOT NULL CONSTRAINT UQ_Customer_Email UNIQUE,
        Phone NVARCHAR(30) NULL,
        City NVARCHAR(80) NOT NULL,
        StateProvince NVARCHAR(80) NOT NULL,
        Country NVARCHAR(80) NOT NULL CONSTRAINT DF_Customer_Country DEFAULT N'United States',
        CreatedDate DATE NOT NULL,
        LoyaltyTier NVARCHAR(20) NOT NULL CONSTRAINT DF_Customer_LoyaltyTier DEFAULT N'Bronze'
    );

    CREATE TABLE dbo.Category (
        CategoryId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Category PRIMARY KEY,
        CategoryName NVARCHAR(80) NOT NULL CONSTRAINT UQ_Category_CategoryName UNIQUE,
        Description NVARCHAR(300) NOT NULL,
        IsActive BIT NOT NULL CONSTRAINT DF_Category_IsActive DEFAULT 1
    );

    CREATE TABLE dbo.Product (
        ProductId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Product PRIMARY KEY,
        CategoryId INT NOT NULL,
        ProductName NVARCHAR(120) NOT NULL,
        SKU NVARCHAR(40) NOT NULL CONSTRAINT UQ_Product_SKU UNIQUE,
        UnitPrice DECIMAL(10,2) NOT NULL,
        Cost DECIMAL(10,2) NOT NULL,
        InventoryQuantity INT NOT NULL,
        IsActive BIT NOT NULL CONSTRAINT DF_Product_IsActive DEFAULT 1,
        CreatedDate DATE NOT NULL,
        CONSTRAINT FK_Product_Category FOREIGN KEY (CategoryId) REFERENCES dbo.Category(CategoryId)
    );

    CREATE TABLE dbo.[Order] (
        OrderId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Order PRIMARY KEY,
        CustomerId INT NOT NULL,
        OrderDate DATETIME2(0) NOT NULL,
        OrderStatus NVARCHAR(30) NOT NULL,
        Subtotal DECIMAL(12,2) NOT NULL,
        TaxAmount DECIMAL(12,2) NOT NULL,
        ShippingAmount DECIMAL(12,2) NOT NULL,
        TotalAmount DECIMAL(12,2) NOT NULL,
        SalesChannel NVARCHAR(30) NOT NULL,
        CONSTRAINT FK_Order_Customer FOREIGN KEY (CustomerId) REFERENCES dbo.Customer(CustomerId)
    );

    CREATE TABLE dbo.OrderItem (
        OrderItemId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_OrderItem PRIMARY KEY,
        OrderId INT NOT NULL,
        ProductId INT NOT NULL,
        Quantity INT NOT NULL,
        UnitPrice DECIMAL(10,2) NOT NULL,
        DiscountAmount DECIMAL(10,2) NOT NULL CONSTRAINT DF_OrderItem_DiscountAmount DEFAULT 0,
        LineTotal DECIMAL(12,2) NOT NULL,
        CONSTRAINT FK_OrderItem_Order FOREIGN KEY (OrderId) REFERENCES dbo.[Order](OrderId),
        CONSTRAINT FK_OrderItem_Product FOREIGN KEY (ProductId) REFERENCES dbo.Product(ProductId)
    );

    CREATE TABLE dbo.Payment (
        PaymentId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Payment PRIMARY KEY,
        OrderId INT NOT NULL,
        PaymentMethod NVARCHAR(30) NOT NULL,
        PaymentStatus NVARCHAR(30) NOT NULL,
        Amount DECIMAL(12,2) NOT NULL,
        PaymentDate DATETIME2(0) NULL,
        TransactionReference NVARCHAR(80) NULL,
        CONSTRAINT FK_Payment_Order FOREIGN KEY (OrderId) REFERENCES dbo.[Order](OrderId)
    );

    CREATE TABLE dbo.Shipment (
        ShipmentId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Shipment PRIMARY KEY,
        OrderId INT NOT NULL,
        Carrier NVARCHAR(40) NOT NULL,
        TrackingNumber NVARCHAR(80) NULL,
        ShipmentStatus NVARCHAR(30) NOT NULL,
        ShippedDate DATETIME2(0) NULL,
        DeliveryDate DATETIME2(0) NULL,
        ShippingCity NVARCHAR(80) NOT NULL,
        ShippingStateProvince NVARCHAR(80) NOT NULL,
        CONSTRAINT FK_Shipment_Order FOREIGN KEY (OrderId) REFERENCES dbo.[Order](OrderId)
    );

    CREATE TABLE dbo.Review (
        ReviewId INT IDENTITY(1,1) NOT NULL CONSTRAINT PK_Review PRIMARY KEY,
        ProductId INT NOT NULL,
        CustomerId INT NOT NULL,
        Rating INT NOT NULL,
        ReviewTitle NVARCHAR(120) NOT NULL,
        ReviewDate DATE NOT NULL,
        IsVerifiedPurchase BIT NOT NULL,
        CONSTRAINT FK_Review_Product FOREIGN KEY (ProductId) REFERENCES dbo.Product(ProductId),
        CONSTRAINT FK_Review_Customer FOREIGN KEY (CustomerId) REFERENCES dbo.Customer(CustomerId),
        CONSTRAINT CK_Review_Rating CHECK (Rating BETWEEN 1 AND 5)
    );
END
GO

IF NOT EXISTS (SELECT 1 FROM dbo.Category)
BEGIN
    INSERT INTO dbo.Category (CategoryName, Description, IsActive) VALUES
    (N'Electronics', N'Devices and accessories for home and mobile use', 1),
    (N'Home and Kitchen', N'Appliances and practical items for daily living', 1),
    (N'Apparel', N'Clothing and accessories for everyday wear', 1),
    (N'Fitness', N'Equipment and gear for training and wellness', 1),
    (N'Books', N'Print and digital reading products across common topics', 1);
END
GO

IF NOT EXISTS (SELECT 1 FROM dbo.Customer)
BEGIN
    INSERT INTO dbo.Customer (FullName, Email, Phone, City, StateProvince, Country, CreatedDate, LoyaltyTier) VALUES
    (N'Avery Johnson', N'avery.johnson@example.com', N'415-555-0101', N'San Francisco', N'California', N'United States', '2025-01-08', N'Gold'),
    (N'Mia Thompson', N'mia.thompson@example.com', N'206-555-0102', N'Seattle', N'Washington', N'United States', '2025-01-12', N'Silver'),
    (N'Noah Williams', N'noah.williams@example.com', N'512-555-0103', N'Austin', N'Texas', N'United States', '2025-01-17', N'Bronze'),
    (N'Emma Brown', N'emma.brown@example.com', N'303-555-0104', N'Denver', N'Colorado', N'United States', '2025-01-21', N'Gold'),
    (N'Liam Davis', N'liam.davis@example.com', N'312-555-0105', N'Chicago', N'Illinois', N'United States', '2025-01-28', N'Bronze'),
    (N'Olivia Miller', N'olivia.miller@example.com', N'617-555-0106', N'Boston', N'Massachusetts', N'United States', '2025-02-03', N'Platinum'),
    (N'Lucas Wilson', N'lucas.wilson@example.com', N'212-555-0107', N'New York', N'New York', N'United States', '2025-02-10', N'Silver'),
    (N'Sophia Moore', N'sophia.moore@example.com', N'404-555-0108', N'Atlanta', N'Georgia', N'United States', '2025-02-14', N'Gold'),
    (N'Mason Taylor', N'mason.taylor@example.com', N'305-555-0109', N'Miami', N'Florida', N'United States', '2025-02-18', N'Bronze'),
    (N'Isabella Anderson', N'isabella.anderson@example.com', N'702-555-0110', N'Las Vegas', N'Nevada', N'United States', '2025-02-22', N'Silver'),
    (N'Ethan Thomas', N'ethan.thomas@example.com', N'503-555-0111', N'Portland', N'Oregon', N'United States', '2025-03-01', N'Gold'),
    (N'Amelia Jackson', N'amelia.jackson@example.com', N'602-555-0112', N'Phoenix', N'Arizona', N'United States', '2025-03-07', N'Bronze'),
    (N'James White', N'james.white@example.com', N'615-555-0113', N'Nashville', N'Tennessee', N'United States', '2025-03-11', N'Silver'),
    (N'Charlotte Harris', N'charlotte.harris@example.com', N'919-555-0114', N'Raleigh', N'North Carolina', N'United States', '2025-03-15', N'Gold'),
    (N'Benjamin Martin', N'benjamin.martin@example.com', N'614-555-0115', N'Columbus', N'Ohio', N'United States', '2025-03-20', N'Bronze'),
    (N'Harper Garcia', N'harper.garcia@example.com', N'214-555-0116', N'Dallas', N'Texas', N'United States', '2025-03-25', N'Platinum'),
    (N'Elijah Martinez', N'elijah.martinez@example.com', N'801-555-0117', N'Salt Lake City', N'Utah', N'United States', '2025-04-02', N'Silver'),
    (N'Evelyn Robinson', N'evelyn.robinson@example.com', N'816-555-0118', N'Kansas City', N'Missouri', N'United States', '2025-04-06', N'Gold'),
    (N'Henry Carter', N'henry.carter@example.com', N'313-555-0119', N'Detroit', N'Michigan', N'United States', '2025-04-10', N'Bronze'),
    (N'Abigail Rodriguez', N'abigail.rodriguez@example.com', N'704-555-0120', N'Charlotte', N'North Carolina', N'United States', '2025-04-13', N'Silver'),
    (N'Alexander Lewis', N'alexander.lewis@example.com', N'901-555-0121', N'Memphis', N'Tennessee', N'United States', '2025-04-19', N'Gold'),
    (N'Emily Lee', N'emily.lee@example.com', N'510-555-0122', N'Oakland', N'California', N'United States', '2025-04-23', N'Bronze'),
    (N'Daniel Walker', N'daniel.walker@example.com', N'713-555-0123', N'Houston', N'Texas', N'United States', '2025-04-28', N'Silver'),
    (N'Elizabeth Hall', N'elizabeth.hall@example.com', N'859-555-0124', N'Lexington', N'Kentucky', N'United States', '2025-05-03', N'Gold'),
    (N'Michael Allen', N'michael.allen@example.com', N'804-555-0125', N'Richmond', N'Virginia', N'United States', '2025-05-08', N'Bronze'),
    (N'Sofia Young', N'sofia.young@example.com', N'414-555-0126', N'Milwaukee', N'Wisconsin', N'United States', '2025-05-12', N'Silver'),
    (N'Sebastian Hernandez', N'sebastian.hernandez@example.com', N'407-555-0127', N'Orlando', N'Florida', N'United States', '2025-05-16', N'Gold'),
    (N'Avery King', N'avery.king@example.com', N'317-555-0128', N'Indianapolis', N'Indiana', N'United States', '2025-05-21', N'Bronze'),
    (N'Ella Wright', N'ella.wright@example.com', N'504-555-0129', N'New Orleans', N'Louisiana', N'United States', '2025-05-27', N'Platinum'),
    (N'Jack Lopez', N'jack.lopez@example.com', N'210-555-0130', N'San Antonio', N'Texas', N'United States', '2025-06-02', N'Silver'),
    (N'Grace Hill', N'grace.hill@example.com', N'559-555-0131', N'Fresno', N'California', N'United States', '2025-06-08', N'Gold'),
    (N'Logan Scott', N'logan.scott@example.com', N'412-555-0132', N'Pittsburgh', N'Pennsylvania', N'United States', '2025-06-13', N'Bronze'),
    (N'Chloe Green', N'chloe.green@example.com', N'585-555-0133', N'Rochester', N'New York', N'United States', '2025-06-17', N'Silver'),
    (N'Jackson Adams', N'jackson.adams@example.com', N'402-555-0134', N'Omaha', N'Nebraska', N'United States', '2025-06-22', N'Gold'),
    (N'Victoria Baker', N'victoria.baker@example.com', N'520-555-0135', N'Tucson', N'Arizona', N'United States', '2025-06-27', N'Bronze'),
    (N'Levi Nelson', N'levi.nelson@example.com', N'951-555-0136', N'Riverside', N'California', N'United States', '2025-07-01', N'Silver'),
    (N'Riley Carter', N'riley.carter@example.com', N'216-555-0137', N'Cleveland', N'Ohio', N'United States', '2025-07-06', N'Gold'),
    (N'Wyatt Mitchell', N'wyatt.mitchell@example.com', N'716-555-0138', N'Buffalo', N'New York', N'United States', '2025-07-11', N'Bronze'),
    (N'Lily Perez', N'lily.perez@example.com', N'559-555-0139', N'Bakersfield', N'California', N'United States', '2025-07-16', N'Platinum'),
    (N'Owen Roberts', N'owen.roberts@example.com', N'757-555-0140', N'Norfolk', N'Virginia', N'United States', '2025-07-21', N'Silver'),
    (N'Zoey Turner', N'zoey.turner@example.com', N'918-555-0141', N'Tulsa', N'Oklahoma', N'United States', '2025-07-26', N'Gold'),
    (N'Gabriel Phillips', N'gabriel.phillips@example.com', N'208-555-0142', N'Boise', N'Idaho', N'United States', '2025-08-01', N'Bronze'),
    (N'Nora Campbell', N'nora.campbell@example.com', N'515-555-0143', N'Des Moines', N'Iowa', N'United States', '2025-08-06', N'Silver'),
    (N'Carter Parker', N'carter.parker@example.com', N'909-555-0144', N'San Bernardino', N'California', N'United States', '2025-08-10', N'Gold'),
    (N'Layla Evans', N'layla.evans@example.com', N'401-555-0145', N'Providence', N'Rhode Island', N'United States', '2025-08-15', N'Bronze'),
    (N'Julian Edwards', N'julian.edwards@example.com', N'225-555-0146', N'Baton Rouge', N'Louisiana', N'United States', '2025-08-20', N'Silver'),
    (N'Mila Collins', N'mila.collins@example.com', N'316-555-0147', N'Wichita', N'Kansas', N'United States', '2025-08-24', N'Gold'),
    (N'Luke Stewart', N'luke.stewart@example.com', N'651-555-0148', N'Saint Paul', N'Minnesota', N'United States', '2025-08-29', N'Bronze'),
    (N'Aurora Sanchez', N'aurora.sanchez@example.com', N'775-555-0149', N'Reno', N'Nevada', N'United States', '2025-09-04', N'Platinum'),
    (N'Isaac Morris', N'isaac.morris@example.com', N'505-555-0150', N'Albuquerque', N'New Mexico', N'United States', '2025-09-09', N'Silver');
END
GO

IF NOT EXISTS (SELECT 1 FROM dbo.Product)
BEGIN
    INSERT INTO dbo.Product (CategoryId, ProductName, SKU, UnitPrice, Cost, InventoryQuantity, IsActive, CreatedDate) VALUES
    (1, N'Noise Cancelling Headphones', N'ELEC-NCH-100', 129.99, 72.00, 84, 1, '2025-01-05'),
    (1, N'USB C Docking Station', N'ELEC-DOCK-210', 89.99, 44.00, 52, 1, '2025-01-07'),
    (1, N'Smart Home Speaker', N'ELEC-SPK-320', 69.99, 31.50, 110, 1, '2025-01-09'),
    (1, N'Portable Power Bank', N'ELEC-PWR-440', 39.99, 18.25, 135, 1, '2025-01-12'),
    (2, N'Air Fryer Oven', N'HOME-AIR-100', 119.99, 65.00, 43, 1, '2025-01-15'),
    (2, N'Ceramic Cookware Set', N'HOME-COOK-220', 149.99, 82.00, 37, 1, '2025-01-18'),
    (2, N'Robot Vacuum', N'HOME-VAC-330', 249.99, 151.00, 26, 1, '2025-01-21'),
    (2, N'Espresso Maker', N'HOME-ESP-450', 199.99, 118.00, 31, 1, '2025-01-25'),
    (3, N'Lightweight Rain Jacket', N'APP-RJ-100', 79.99, 38.00, 95, 1, '2025-02-01'),
    (3, N'Everyday Backpack', N'APP-BAG-210', 59.99, 27.00, 140, 1, '2025-02-04'),
    (3, N'Running Socks Pack', N'APP-SOCK-320', 18.99, 7.50, 220, 1, '2025-02-07'),
    (3, N'Polarized Sunglasses', N'APP-SUN-430', 44.99, 19.75, 88, 1, '2025-02-10'),
    (4, N'Adjustable Dumbbell Pair', N'FIT-DUMB-100', 229.99, 132.00, 24, 1, '2025-02-14'),
    (4, N'Yoga Mat Pro', N'FIT-YOGA-210', 49.99, 20.00, 160, 1, '2025-02-17'),
    (4, N'Fitness Tracker Band', N'FIT-TRK-320', 99.99, 53.00, 72, 1, '2025-02-20'),
    (4, N'Indoor Cycling Shoes', N'FIT-SHOE-430', 109.99, 58.00, 48, 1, '2025-02-23'),
    (5, N'Data Analytics Handbook', N'BOOK-DATA-100', 34.99, 13.00, 90, 1, '2025-03-01'),
    (5, N'Modern Java Patterns', N'BOOK-JAVA-210', 42.99, 17.00, 76, 1, '2025-03-03'),
    (5, N'Kitchen Weeknight Recipes', N'BOOK-COOK-320', 24.99, 9.00, 120, 1, '2025-03-05'),
    (5, N'Practical Fitness Guide', N'BOOK-FIT-430', 29.99, 11.00, 105, 1, '2025-03-08');
END
GO

IF NOT EXISTS (SELECT 1 FROM dbo.[Order])
BEGIN
    DECLARE @i INT = 1;
    WHILE @i <= 120
    BEGIN
        DECLARE @customerId INT = ((@i * 7) % 50) + 1;
        DECLARE @orderDate DATETIME2(0) = DATEADD(HOUR, (@i * 5) % 24, DATEADD(DAY, @i % 210, CAST('2025-02-01' AS DATETIME2(0))));
        DECLARE @status NVARCHAR(30) =
            CASE
                WHEN @i % 17 = 0 THEN N'Cancelled'
                WHEN @i % 13 = 0 THEN N'Returned'
                WHEN @i % 11 = 0 THEN N'Processing'
                WHEN @i % 7 = 0 THEN N'Shipped'
                ELSE N'Delivered'
            END;
        DECLARE @channel NVARCHAR(30) =
            CASE @i % 4 WHEN 0 THEN N'Web' WHEN 1 THEN N'Mobile App' WHEN 2 THEN N'Marketplace' ELSE N'Phone' END;

        INSERT INTO dbo.[Order] (CustomerId, OrderDate, OrderStatus, Subtotal, TaxAmount, ShippingAmount, TotalAmount, SalesChannel)
        VALUES (@customerId, @orderDate, @status, 0, 0, 0, 0, @channel);

        DECLARE @orderId INT = SCOPE_IDENTITY();
        DECLARE @itemCount INT = (@i % 4) + 1;
        DECLARE @j INT = 1;

        WHILE @j <= @itemCount
        BEGIN
            DECLARE @productId INT = (((@i * @j) + @j * 3) % 20) + 1;
            DECLARE @quantity INT = ((@i + @j) % 3) + 1;
            DECLARE @unitPrice DECIMAL(10,2) = (SELECT UnitPrice FROM dbo.Product WHERE ProductId = @productId);
            DECLARE @discount DECIMAL(10,2) = CASE WHEN (@i + @j) % 9 = 0 THEN ROUND(@unitPrice * @quantity * 0.10, 2) ELSE 0 END;
            DECLARE @lineTotal DECIMAL(12,2) = (@unitPrice * @quantity) - @discount;

            INSERT INTO dbo.OrderItem (OrderId, ProductId, Quantity, UnitPrice, DiscountAmount, LineTotal)
            VALUES (@orderId, @productId, @quantity, @unitPrice, @discount, @lineTotal);

            SET @j += 1;
        END

        DECLARE @subtotal DECIMAL(12,2) = (SELECT SUM(LineTotal) FROM dbo.OrderItem WHERE OrderId = @orderId);
        DECLARE @tax DECIMAL(12,2) = ROUND(@subtotal * 0.0825, 2);
        DECLARE @shipping DECIMAL(12,2) = CASE WHEN @subtotal >= 100 OR @status = N'Cancelled' THEN 0 ELSE 7.99 END;
        DECLARE @total DECIMAL(12,2) = @subtotal + @tax + @shipping;

        UPDATE dbo.[Order]
        SET Subtotal = @subtotal,
            TaxAmount = @tax,
            ShippingAmount = @shipping,
            TotalAmount = @total
        WHERE OrderId = @orderId;

        INSERT INTO dbo.Payment (OrderId, PaymentMethod, PaymentStatus, Amount, PaymentDate, TransactionReference)
        VALUES (
            @orderId,
            CASE @i % 5 WHEN 0 THEN N'Credit Card' WHEN 1 THEN N'Debit Card' WHEN 2 THEN N'PayPal' WHEN 3 THEN N'Gift Card' ELSE N'Bank Transfer' END,
            CASE WHEN @status = N'Cancelled' THEN N'Voided' WHEN @i % 19 = 0 THEN N'Failed' WHEN @i % 23 = 0 THEN N'Pending' ELSE N'Captured' END,
            CASE WHEN @status = N'Cancelled' THEN 0 ELSE @total END,
            CASE WHEN @status = N'Cancelled' OR @i % 23 = 0 THEN NULL ELSE DATEADD(MINUTE, 15 + @i, @orderDate) END,
            CASE WHEN @status = N'Cancelled' OR @i % 23 = 0 THEN NULL ELSE CONCAT(N'ECOM-', FORMAT(@orderId, '000000'), N'-', RIGHT(CONVERT(NVARCHAR(36), NEWID()), 8)) END
        );

        INSERT INTO dbo.Shipment (OrderId, Carrier, TrackingNumber, ShipmentStatus, ShippedDate, DeliveryDate, ShippingCity, ShippingStateProvince)
        SELECT
            @orderId,
            CASE @i % 4 WHEN 0 THEN N'UPS' WHEN 1 THEN N'FedEx' WHEN 2 THEN N'USPS' ELSE N'DHL' END,
            CASE WHEN @status IN (N'Cancelled', N'Processing') THEN NULL ELSE CONCAT(N'TRK', FORMAT(@orderId, '000000'), @i) END,
            CASE
                WHEN @status = N'Cancelled' THEN N'Cancelled'
                WHEN @status = N'Processing' THEN N'Pending'
                WHEN @status = N'Shipped' THEN N'In Transit'
                WHEN @status = N'Returned' THEN N'Returned'
                ELSE N'Delivered'
            END,
            CASE WHEN @status IN (N'Cancelled', N'Processing') THEN NULL ELSE DATEADD(DAY, 1 + (@i % 3), @orderDate) END,
            CASE WHEN @status = N'Delivered' THEN DATEADD(DAY, 4 + (@i % 5), @orderDate) ELSE NULL END,
            City,
            StateProvince
        FROM dbo.Customer
        WHERE CustomerId = @customerId;

        SET @i += 1;
    END
END
GO

IF NOT EXISTS (SELECT 1 FROM dbo.Review)
BEGIN
    DECLARE @r INT = 1;
    WHILE @r <= 90
    BEGIN
        INSERT INTO dbo.Review (ProductId, CustomerId, Rating, ReviewTitle, ReviewDate, IsVerifiedPurchase)
        VALUES (
            ((@r * 5) % 20) + 1,
            ((@r * 11) % 50) + 1,
            CASE WHEN @r % 13 = 0 THEN 2 WHEN @r % 10 = 0 THEN 3 WHEN @r % 7 = 0 THEN 4 ELSE 5 END,
            CASE WHEN @r % 13 = 0 THEN N'Could be better' WHEN @r % 10 = 0 THEN N'Good for the price' WHEN @r % 7 = 0 THEN N'Works as expected' ELSE N'Excellent purchase' END,
            DATEADD(DAY, @r % 180, CAST('2025-03-01' AS DATE)),
            CASE WHEN @r % 6 = 0 THEN 0 ELSE 1 END
        );
        SET @r += 1;
    END
END
GO

USE master;
GO

IF NOT EXISTS (SELECT 1 FROM sys.sql_logins WHERE name = N'ecom_dab_reader')
BEGIN
    CREATE LOGIN ecom_dab_reader WITH PASSWORD = '$(ECOM_DAB_READER_PASSWORD)', CHECK_POLICY = ON, CHECK_EXPIRATION = OFF;
END
GO

USE EcommercePocDb;
GO

IF NOT EXISTS (SELECT 1 FROM sys.database_principals WHERE name = N'ecom_dab_reader')
BEGIN
    CREATE USER ecom_dab_reader FOR LOGIN ecom_dab_reader;
END
GO

ALTER ROLE db_datareader ADD MEMBER ecom_dab_reader;
GO

SELECT 'Customer' AS TableName, COUNT(*) AS [RowCount] FROM dbo.Customer
UNION ALL SELECT 'Category', COUNT(*) FROM dbo.Category
UNION ALL SELECT 'Product', COUNT(*) FROM dbo.Product
UNION ALL SELECT 'Order', COUNT(*) FROM dbo.[Order]
UNION ALL SELECT 'OrderItem', COUNT(*) FROM dbo.OrderItem
UNION ALL SELECT 'Payment', COUNT(*) FROM dbo.Payment
UNION ALL SELECT 'Shipment', COUNT(*) FROM dbo.Shipment
UNION ALL SELECT 'Review', COUNT(*) FROM dbo.Review
ORDER BY TableName;
GO
