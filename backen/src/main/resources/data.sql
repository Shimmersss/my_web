INSERT INTO article (title, content)
SELECT '欢迎使用', '这是第一篇测试文章'
WHERE NOT EXISTS (SELECT 1 FROM article WHERE title = '欢迎使用');

INSERT INTO article (title, content)
SELECT '关于我们', '企业简介内容'
WHERE NOT EXISTS (SELECT 1 FROM article WHERE title = '关于我们');
