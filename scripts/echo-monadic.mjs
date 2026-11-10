/*
Pinned bc-modding-utilities src/monadic.js, revision 6346353fe419fbcfb668d32e56021970b419c167.
MIT License
Copyright (c) 2025 SugarChain Studio
Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:
The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.
THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
/** @type {(value: any) => boolean} */
const isNullish = (value) => value === null || value === undefined;

/**
 * @template T
 * @template {Object} [Ctx = {}]
 */
export class Optional {
    /**
     * @param {T | undefined} value
     * @param {Ctx} [ctx]
     */
    constructor(value, ctx = /** @type {Ctx} */ ({})) {
        this.value = value;
        this.ctx = ctx;
    }

    /**
     * A typical monadic `then` method. If the value is undefined, it returns nullOpt.
     * Otherwise, it calls the callback with the value and context, and returns a new Optional
     * with the result. If the callback returns undefined, it returns nullOpt.
     * @template R
     * @overload
     * @param {(value: T, ctx: Ctx) => R | undefined} callback
     * @returns {Optional<R, Ctx>}
     */

    /**
     * An extended version of the `then` method that allows specifying a context name.
     * If the callback is called and returns a value, it is stored in the context under the specified name.
     * @template {string} K
     * @template R
     * @overload
     * @param {K} ctxNameOrCallback
     * @param {(value: T, ctx: Ctx) => R | undefined} [callback]
     * @returns {Optional<R, Ctx & { [key in K]: R }>}
     */

    /**
     * @template {string} K
     * @template R
     * @param {K | ((value: T, ctx: Ctx) => R | undefined)} ctxNameOrCallback
     * @param {(value: T, ctx: Ctx) => R | undefined} [callback]
     */
    then(ctxNameOrCallback, callback) {
        if (isNullish(this.value)) return nullOpt;
        const cb =
            typeof ctxNameOrCallback === "function"
                ? ctxNameOrCallback
                : callback;
        const result = cb(this.value, this.ctx);
        if (isNullish(result)) return nullOpt;
        if (typeof ctxNameOrCallback === "string") {
            /** @type {any}*/ (this.ctx)[ctxNameOrCallback] = result;
        }
        return new Optional(result, this.ctx);
    }

    /**
     * Filters the value using the provided predicate. If the predicate returns anything true, the value is kept.
     * @param {(value:T, ctx:Ctx) => any} pred
     */
    filter(pred) {
        if (isNullish(this.value)) return nullOpt;
        if (pred(this.value, this.ctx)) {
            return this;
        } else {
            return /** @type {Optional<T,Ctx>} */ (nullOpt);
        }
    }

    /**
     * Returns the value if it exists, otherwise returns the result of calling the defaultValue function.
     * @template R
     * @param {() => R} defaultValue
     * @returns {R | T}
     */
    valueOr(defaultValue) {
        if (isNullish(this.value)) return defaultValue();
        return this.value;
    }
}

const nullOpt = new Optional(undefined);

/**
 * Creates a monadic Optional instance from a value.
 * Returns an Optional with the value and an empty context.
 * If the value is undefined or null, it returns a nullOpt.
 * @template T
 * @overload
 * @param { T | undefined | null } arg0
 * @returns {Optional<T, {}>} An Optional instance with the value and an empty context.
 */
/**
 * Creates a monadic Optional instance from a value.
 * Returns an Optional with the value and a context containing the specified key.
 * If the value is undefined or null, it returns a nullOpt.
 * @template T
 * @template {string} K
 * @overload
 * @param { K } arg0 - The key to be used in the context.
 * @param { T | undefined | null } arg1 - The value to be wrapped in the Optional.
 * @returns {Optional<T, {[k in K]: T}>} - An Optional instance with the value and context containing the key.
 */
/**
 * @template T
 * @template {string} K
 * @param { K | T | undefined | null } arg0
 * @param { T | undefined | "__dxglhj"} [arg1]
 */
export function monadic(arg0, arg1 = "__dxglhj") {
    const rOpt = arg1 === "__dxglhj" ? arg0 : arg1;
    if (isNullish(rOpt)) return /** @type {Optional<T, {}>} */ (nullOpt);
    if (typeof arg0 === "string") {
        return /** @type {Optional<T, {[k in K]: T}>} */ (
            new Optional(rOpt, /** @type { {[k in K]: T}} */ ({ [arg0]: rOpt }))
        );
    }
    return /** @type {Optional<T, {}>} */ (new Optional(rOpt, {}));
}
