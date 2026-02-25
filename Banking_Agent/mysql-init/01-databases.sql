-- Create required Fineract databases
CREATE DATABASE IF NOT EXISTS `fineract_tenants`;

CREATE DATABASE IF NOT EXISTS `fineract_default`;

-- Grant all privileges to root from any host
GRANT ALL ON *.* TO 'root' @'%';