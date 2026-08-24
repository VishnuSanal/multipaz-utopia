const ICON_ERROR =
    '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" ' +
    'stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">' +
    '<circle cx="12" cy="12" r="10"/><path d="M12 8v4.5M12 16h.01"/></svg>';

// Asks the wallet for the payment card and renders the account it names. No account number is
// sent -- the server reads it from the signed credential. Refreshing means presenting again.
async function viewAccount() {
    clearStatus();
    const buttons = document.querySelectorAll("#lookup-btn, #refresh-btn");
    for (const button of buttons) {
        button.disabled = true;
    }
    try {
        const response = await multipazVerifyCredentials({intent: "account_lookup"});
        if (response && response.error) {
            // A failure reported after the card was presented: untrusted issuer, unknown
            // account, or the registry being unreachable.
            showError(response.error, response.error_description);
        } else {
            showStatement(response);
        }
    } catch (e) {
        // The wallet was dismissed, or the request never got off the ground.
        showError(null, "Your account could not be loaded. Please try again.");
    } finally {
        for (const button of buttons) {
            button.disabled = false;
        }
    }
}

function showStatement(data) {
    document.getElementById("balance").textContent = formatAmount(data.balance);
    document.getElementById("balance").classList.toggle("negative", data.balance < 0);

    const meta = [];
    if (data.holder_name) {
        meta.push(data.holder_name);
    }
    if (data.account_number) {
        meta.push("Account " + data.account_number);
    }
    document.getElementById("account-line").textContent = meta.join(" · ");

    const list = document.getElementById("transactions");
    list.textContent = "";
    // The registry already returns transactions newest-first, so render in the order given.
    const transactions = data.transactions || [];
    for (const transaction of transactions) {
        list.appendChild(renderTransaction(transaction));
    }
    document.getElementById("no-transactions").hidden = transactions.length > 0;

    document.getElementById("intro").hidden = true;
    document.getElementById("statement").hidden = false;
}

// One transaction row. Direction is implied by which counterparty key the registry set:
// "to" means money left this account, "from" means it arrived.
function renderTransaction(transaction) {
    const outgoing = !!transaction.to;
    const counterparty = outgoing ? transaction.to : transaction.from;

    const row = document.createElement("div");
    row.className = "transaction";

    const details = document.createElement("div");
    details.className = "transaction-details";

    const name = document.createElement("div");
    name.className = "transaction-name";
    name.textContent = (counterparty && counterparty.name) || "Unknown";
    details.appendChild(name);

    const note = document.createElement("div");
    note.className = "transaction-note";
    const parts = [];
    if (transaction.description) {
        parts.push(transaction.description);
    }
    parts.push(formatTime(transaction.time));
    note.textContent = parts.join(" · ");
    details.appendChild(note);

    const amount = document.createElement("div");
    amount.className = "transaction-amount " + (outgoing ? "outgoing" : "incoming");
    // The sign is carried by the direction prefix, so the number itself is unsigned here.
    amount.textContent = (outgoing ? "−" : "+") + formatAmount(Math.abs(transaction.amount));

    row.appendChild(details);
    row.appendChild(amount);
    return row;
}

function showError(error, description) {
    const detail = description || "Your account could not be loaded.";
    const line = document.createElement("span");
    if (error) {
        const code = document.createElement("strong");
        code.textContent = "Error Code: " + error + " ";
        line.appendChild(code);
        line.appendChild(document.createTextNode("- " + detail));
    } else {
        line.textContent = detail;
    }
    renderBanner("error", ICON_ERROR, "Account Unavailable", [line]);
}

function renderBanner(kind, icon, title, lines) {
    const banner = document.getElementById("banner");
    banner.className = "banner " + kind;

    const titleEl = document.createElement("div");
    titleEl.className = "banner-title";
    titleEl.innerHTML = icon;
    titleEl.appendChild(document.createTextNode(title));
    banner.appendChild(titleEl);

    for (const line of lines) {
        const lineEl = document.createElement("div");
        lineEl.className = "banner-line";
        if (typeof line === "string") {
            lineEl.textContent = line;
        } else {
            lineEl.appendChild(line);
        }
        banner.appendChild(lineEl);
    }
    banner.hidden = false;
}

function clearStatus() {
    const banner = document.getElementById("banner");
    banner.hidden = true;
    banner.innerHTML = "";
}

// UPay settles in USD. The minus sits outside the symbol: "−$12.50", not "$−12.50".
function formatAmount(amount) {
    const value = Number(amount) || 0;
    const digits = Math.abs(value).toLocaleString('en-US', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2,
    });
    return (value < 0 ? "−" : "") + "$" + digits;
}

// Registry times are ISO-8601. Fall back to the raw string rather than showing "Invalid Date".
function formatTime(time) {
    const parsed = new Date(time);
    if (isNaN(parsed.getTime())) {
        return time || "";
    }
    return parsed.toLocaleString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric',
        hour: 'numeric',
        minute: '2-digit',
    });
}
