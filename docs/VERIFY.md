# Run and verify ChatSphere

## Start the app

Extract the whole ZIP to a writable folder. Open a terminal there and run `java -version`. The application requires Java 21 or later.

- **Windows x64:** Double-click `start-server.cmd` and wait for “ChatSphere server ready on port 5050.” Leave the window open. Double-click `start-client.cmd` twice to open two chat clients.
- **Linux x64:** Run `./start-server.sh` in a terminal. After it reports ready, run `./start-client.sh` in two more terminals. Start the clients from your graphical desktop session.

On one computer, leave the host set to `localhost`. Between two computers on the same Wi-Fi or Ethernet, start one server. On the other computer, open **Connection settings**, enter the server's local IPv4 address and port `5050`, then click **Connect**. Allow the server's TCP port through the private-network firewall if needed. Both clients need to show **Connected** before they can register or sign in.

The portable ZIPs include the app's Maven runtime dependencies. They use an installed Java runtime and can launch without Internet access. ChatSphere uses plain TCP and is intended for a trusted private network.

## Verify the features

1. Select **Create an account** in each client. Choose two different usernames with at least three characters and passwords with at least eight. Confirm each password. Registration opens the workspace. After signing out, try a wrong password, then sign in with the correct one.
2. Create a room in one client. In the other, select **Browse**, join the room, and open it. Type briefly to show the typing indicator in the other window. Send a message and confirm it appears on both sides.
3. With the room selected, click **Attach** and select `demo-note.txt`, then repeat with a PNG or JPEG under 5 MiB. The receiving client shows the filenames. Select **Save attachment…** to save a copy of each. Compare the saved file with the original.
4. Select **Direct** and open the other account. Send a message and an attachment. Open the conversation on the receiving side and check the sender's delivered and read indicators.
5. Sign out of the receiving client and check its offline status in the sender's Direct list. Send another direct message and file. Sign back in to the receiving account, check the unread count, open the chat, and save the file from history.
6. Leave the room, rejoin it via **Browse**, and open it to see earlier messages and attachments. Use **Search messages** to search by keyword or filename, then narrow the results by sender, date, or conversation.
7. Close both clients and stop the server. Restart the server from the same extracted folder and sign in again. Check that accounts, room membership, history, and saved attachment links survived. The server stores them in `chatsphere.db`.

For startup errors, check the console and the address on the sign-in screen. If port 5050 is in use, run the server command with `--port=5060` and pass the same flag to both client commands or set the port in **Connection settings**.
