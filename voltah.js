const express = require('express');
const app = express();
__path = process.cwd()
const bodyParser = require("body-parser");
const fs = require('fs');
const path = require('path');
const PORT = process.env.PORT || 8000;

let server = require('./qr'),
    code = require('./pair');

// Increase event listeners limit
require('events').EventEmitter.defaultMaxListeners = 500;

// Middleware
app.use(bodyParser.json());
app.use(bodyParser.urlencoded({ extended: true }));

// Routes
app.use('/qr', server);
app.use('/code', code);

// Credentials download endpoint
app.get('/download-creds/:sessionId', async (req, res) => {
    const sessionId = req.params.sessionId;
    const filePath = path.join(__dirname, 'temp', sessionId, 'creds.json');
    
    if (fs.existsSync(filePath)) {
        res.download(filePath, 'creds.json', (err) => {
            if (err) {
                console.error('Error downloading file:', err);
                res.status(500).send('Error downloading file');
            }
            // Clean up after download
            fs.rmSync(path.join(__dirname, 'temp', sessionId), { recursive: true, force: true });
        });
    } else {
        res.status(404).send('Session not found or already expired');
    }
});

// HTML pages
app.use('/pair', (req, res) => {
    res.sendFile(path.join(__path, 'pair.html'));
});

app.use('/', (req, res) => {
    res.sendFile(path.join(__path, 'main.html'));
});

// Create temp directory if it doesn't exist
if (!fs.existsSync(path.join(__dirname, 'temp'))) {
    fs.mkdirSync(path.join(__dirname, 'temp'));
}

app.listen(PORT, () => {
    console.log(`
Server running on http://localhost:${PORT}
Don't Forget To Give Star ⭐ to the Repo`);
});

module.exports = app;