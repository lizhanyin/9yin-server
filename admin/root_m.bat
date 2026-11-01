@echo off


bin\mysqld.exe --skip-grant-tables --shared-memory --datadir=".\data"


rem bin\mysql -u root
rem FLUSH PRIVILEGES;
rem UPDATE mysql.user SET authentication_string='' WHERE User='root' AND Host='localhost';
rem ALTER USER 'root'@'localhost' IDENTIFIED BY '123456';