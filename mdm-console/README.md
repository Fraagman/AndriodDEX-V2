# MDM Console

Enterprise MDM Console for AndroidDex Zero Client.

## Setup Instructions

1. Install dependencies:
   ```bash
   npm install
   ```

2. Initialize the database:
   ```bash
   node scripts/init-db.js
   ```

3. Start the development server:
   ```bash
   npm run dev
   ```

4. Open [http://localhost:3000](http://localhost:3000) with your browser to see the result.

## Store Listings
To generate real app screenshots for store listings, run the application, then run `node scripts/take-screenshots.js` (requires puppeteer).
