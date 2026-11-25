// Stands in for Varnish in test/integration/run.sh: answers every request
// with a plain 200 response, and prints the request so the test can check it.
const http = require('http');

http.createServer((request, response) => {
    console.log(request.method, request.headers['x-magento-tags-pattern']);
    response.writeHead(200, {'Content-Type': 'text/plain'});
    response.end('Purged\n');
}).listen(6081);
