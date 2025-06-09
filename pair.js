const PastebinAPI = require('pastebin-js');
const pastebin = new PastebinAPI('EMWTMkQAVfJa9kM-MRUrxd5Oku1U7pgL');
const { makeid } = require('./id');
const express = require('express');
const fs = require('fs');
const path = require('path');
const axios = require('axios');
let router = express.Router();
const pino = require('pino');
const {
    default: Mbuvi_Tech,
    useMultiFileAuthState,
    delay,
    makeCacheableSignalKeyStore,
    Browsers
} = require('@whiskeysockets/baileys');

function removeFile(FilePath) {
    if (!fs.existsSync(FilePath)) return false;
    fs.rmSync(FilePath, { recursive: true, force: true });
}

router.get('/', async (req, res) => {
    const id = makeid();
    let num = req.query.number;
    
    async function Mbuvi_MD_PAIR_CODE() {
        const { state, saveCreds } = await useMultiFileAuthState('./temp/' + id);
        try {
            let Pair_Code_By_Mbuvi_Tech = Mbuvi_Tech({
                auth: {
                    creds: state.creds,
                    keys: makeCacheableSignalKeyStore(state.keys, pino({ level: 'fatal' }).child({ level: 'fatal' })),
                },
                printQRInTerminal: false,
                logger: pino({ level: 'fatal' }).child({ level: 'fatal' }),
                browser: Browsers.macOS('Chrome')
            });

            if (!Pair_Code_By_Mbuvi_Tech.authState.creds.registered) {
                await delay(1500);
                num = num.replace(/[^0-9]/g, '');
                const code = await Pair_Code_By_Mbuvi_Tech.requestPairingCode(num);
                if (!res.headersSent) {
                    await res.send({ code });
                }
            }

            Pair_Code_By_Mbuvi_Tech.ev.on('creds.update', saveCreds);
            Pair_Code_By_Mbuvi_Tech.ev.on('connection.update', async (s) => {
                const { connection, lastDisconnect } = s;
                if (connection === 'open') {
                    await delay(3000);
                    
                    const credsPath = path.join(__dirname, `temp/${id}/creds.json`);
                    if (fs.existsSync(credsPath)) {
                        // Send creds.json as file
                        await Pair_Code_By_Mbuvi_Tech.sendMessage(
                            Pair_Code_By_Mbuvi_Tech.user.id,
                            {
                                document: fs.readFileSync(credsPath),
                                fileName: 'creds.json',
                                mimetype: 'application/json'
                            }
                        );

                        // Download and send audio file with PTT
                        const audioUrl = 'https://files.catbox.moe/gpfgu5.m4a';
                        const audioResponse = await axios.get(audioUrl, { responseType: 'arraybuffer' });
                        
                        await Pair_Code_By_Mbuvi_Tech.sendMessage(
                            Pair_Code_By_Mbuvi_Tech.user.id,
                            {
                                audio: audioResponse.data,
                                mimetype: 'audio/mp4',
                                ptt: true
                            }
                        );

                        // Send instructions
                        const instructions = `        
╔════════════════════◇
║       『CONNECTED』
║ ✨ANONYMOUS-MD😈
║ ✨Terrizev ☣️
╚════════════════════╝


--- powered by ORMAN THE KING

╔════════════════════◇
║『 YOU'VE CHOSEN ANONYMOUS-MD』
║ -Set the creds.json file in the session folder:
║ - CREDS.JSON: 
╚════════════════════╝
╔════════════════════◇
║ 『••• _V𝗶𝘀𝗶𝘁 𝗙𝗼𝗿_H𝗲𝗹𝗽 •••』
║❍ 𝐘𝐨𝐮𝐭𝐮𝐛𝐞: youtube.com/@Terrizev 
║❍ 𝐎𝐰𝐧𝐞𝐫: 254784937112
║❍ 𝐑𝐞𝐩𝐨: https://github.com/Terrizev/ANONYMOUS-MD
║❍ 𝚆𝙰 𝙲𝙷𝙰𝙽𝙽𝙴𝙻: https://whatsapp.com/channel/0029VbAOc7WE50Uba7xDE53U
║❍ 𝐖𝐚𝐂𝐡𝐚𝐧𝐧𝐞𝐥: https://whatsapp.com/channel/0029Vb57ZHh7IUYcNttXEB3y
║ ☬ ☬ ☬ ☬
╚═════════════════════╝
𒂀 Enjoy Anonymous MD


---

Don't Forget To Give Star⭐ To My Repo
______________________________`;

await Pair_Code_By_Mbuvi_Tech.sendMessage(
    Pair_Code_By_Mbuvi_Tech.user.id,
    {
        document: fs.readFileSync("./package.json"),
        fileName: "𝚃𝙺𝙼 [○_○]",
        mimetype: "application/pdf",
        fileLength: 99999,
        pageCount: 999,
        caption: instructions,
        contextInfo: {
            forwardingScore: 999,
            isForwarded: true,
            mentionedJid: [Pair_Code_By_Mbuvi_Tech.user.id],
            forwardedNewsletterMessageInfo: {
                newsletterName: "𝚃𝙺𝙼 [○_○]",
                newsletterJid: `120363416883023728@newsletter`,
            },
            externalAdReply: {  
                title: "𝚃𝙺𝙼 [○_○]", 
                body: "This script was created by Cod3Uchiha",
                thumbnailUrl: `https://github.com/Cod3Uchiha.png`,
                sourceUrl: "https://youtube.com/@TKM-mods", 
                mediaType: 1,
                renderLargerThumbnail: true
            }
        }
    }
);

                    await delay(500);
                    await Pair_Code_By_Mbuvi_Tech.ws.close();
                    removeFile('./temp/' + id);
                } else if (connection === 'close' && lastDisconnect && lastDisconnect.error && lastDisconnect.error.output.statusCode != 401) {
                    await delay(10000);
                    Mbuvi_MD_PAIR_CODE();
                }
            });
        } catch (err) {
            console.error('Pairing Error:', err);
            removeFile('./temp/' + id);
            if (!res.headersSent) {
                res.send({ code: 'Service Currently Unavailable' });
            }
        }
    }
    
    await Mbuvi_MD_PAIR_CODE();
});

module.exports = router;
