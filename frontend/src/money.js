/** US dollars with two decimals, e.g. $12.34. */
export const usd=value=>new Intl.NumberFormat('en-US',{style:'currency',currency:'USD'}).format(value);
