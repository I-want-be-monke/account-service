CREATE ROLE account_runtime NOLOGIN;
CREATE ROLE import_test_app LOGIN PASSWORD 'test-import-runtime-password' IN ROLE account_runtime;
