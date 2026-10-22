-- New catalogue prices are explicitly chosen INR prices, not a currency conversion.
-- Historical orders retain their recorded currency, amount and item snapshots.
UPDATE products SET currency='INR',price=CASE id
 WHEN 'mug' THEN 49900 WHEN 'notebook' THEN 29900 WHEN 'shirt' THEN 99900 WHEN 'keyboard' THEN 299900
 ELSE price END,version=version+1;
