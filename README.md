# Currency Converter

A web app with a Java backend. It uses Java's built-in `java.net.http.HttpClient` to fetch live exchange rates
from [ExchangeRate-API](https://www.exchangerate-api.com/) and convert amounts. No libraries or build tools needed.

## Requirements
- Java 16 or newer

## Run
```
java CurrencyConverterApp.java
```
Open http://localhost:8080

Run the command from this folder. The app serves `public/index.html` when present; otherwise, it serves `index.html` from this folder.

## Optional: use your own API key
The app uses the free open endpoint (`open.er-api.com`) by default. To use the keyed
`v6.exchangerate-api.com` endpoint, set your key:
```
# macOS / Linux
EXCHANGE_API_KEY=your_key java CurrencyConverterApp.java
# Windows PowerShell
$env:EXCHANGE_API_KEY="your_key"; java CurrencyConverterApp.java
```
Change the port with the `PORT` environment variable.

## How it works
- `GET /api/currencies` returns the list of supported currency codes.
- `GET /api/convert?from=USD&to=INR&amount=100` fetches rates with `HttpClient` and returns the result as JSON.
- Rates are cached for 10 minutes to stay within API limits.
- `public/index.html` is the UI: amount, from/to dropdowns, swap button, and live conversion as you type.
