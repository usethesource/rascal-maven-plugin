package org.rascalmpl.tutor;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.rascalmpl.tutor.lang.rascal.tutor.repl.ITutorScreenshotFeature;
import org.rascalmpl.uri.URIResolverRegistry;
import org.rascalmpl.uri.URIUtil;

import io.usethesource.vallang.ISourceLocation;

/**
 * This class provides ITutorScreenshotFeature to the tutor code in the Rascal project.
 * It depends on Selenium at compile-time which takes chrome-driver at run-time which
 * uses Chrome again.
 */
public class Screenshotter implements ITutorScreenshotFeature {
    private static final String BROWSER_BINARY = System.getProperty("webdriver.chrome.browser");
    private static final String DRIVER_BINARY = System.getProperty("webdriver.chrome.driver");
    private final ChromeDriverService service;
    private final RemoteWebDriver driver;

    public Screenshotter() throws IOException {
        String driver = DRIVER_BINARY;
        String browser = BROWSER_BINARY;

        if (driver == null) {
            driver = inferChromeDriverBinaryLocation();
        }

        if (browser == null) {
            browser = inferChromeBrowserBinaryLocation();
        }

        if (driver != null && browser != null) {
            this.service = new ChromeDriverService.Builder()
                .usingDriverExecutable(new File(driver))
                .usingAnyFreePort()
                .build();

            this.service.start();
            this.driver = getBrowser(service, browser);
        }
        else {
            this.service = null;
            this.driver = null;
            printInfoMessage();
        }
    }

    private void printInfoMessage() {
        System.err.println("INFO: tutor screenshot feature is currently disabled. To enable:");
        System.err.println("\t* add the folder holding `chromedriver` to your PATH;");
        System.err.println("\t* add the foldering holding `chrome` or `Google Chrome for Testing` to your PATH;");
        System.err.println("\t* or use: `-Dwebdriver.chrome.browser=/path/to/chrome -Dwebdriver.chrome.driver/path/to/chromedriver`");
        System.err.println("INFO: chrome and the chromedriver need to be aligned exactly. See https://googlechromelabs.github.io/chrome-for-testing/");
    }

    private String inferChromeBrowserBinaryLocation() throws IOException {
        ISourceLocation pathBrowser = URIUtil.correctLocation("PATH", "", "Google Chrome for Testing");

        if (URIResolverRegistry.getInstance().exists(pathBrowser)) {
            System.err.println("driver exists: " + pathBrowser);
            return URIResolverRegistry.getInstance().logicalToPhysical(pathBrowser).getPath();
        }

        pathBrowser = URIUtil.correctLocation("PATH", "", "chrome");
        if (URIResolverRegistry.getInstance().exists(pathBrowser)) {
            System.err.println("driver exists: " + pathBrowser);
            return URIResolverRegistry.getInstance().logicalToPhysical(pathBrowser).getPath();
        }

        return null;
    }

    private String inferChromeDriverBinaryLocation() throws IOException {
        ISourceLocation pathDriver = URIUtil.correctLocation("PATH", "", "chromedriver");

        if (URIResolverRegistry.getInstance().exists(pathDriver)) {
            System.err.println("driver exists: " + pathDriver);
            return URIResolverRegistry.getInstance().logicalToPhysical(pathDriver).getPath();
        }

        return null;
    }

    private static RemoteWebDriver getBrowser(ChromeDriverService service, String BROWSER_BINARY) {
        ChromeOptions options = new ChromeOptions()
            .setBinary(BROWSER_BINARY)
            .addArguments("--headless", "--disable-gpu", "--window-size=1900,1200","--ignore-certificate-errors","--disable-extensions","--no-sandbox","--disable-dev-shm-usage")
            .addArguments("--user-data-dir=/tmp/rascal-config/google-chrome")
            ;

        RemoteWebDriver driver = new RemoteWebDriver(service.getUrl(), options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(30));
        driver.manage().timeouts().scriptTimeout(Duration.ofSeconds(60));
        driver.manage().window().maximize();

        return driver;
    }

    @Override
    protected void finalize() throws Throwable {
        if (driver != null) {
            driver.quit();
        }

        if (service != null) {
            service.stop();
        }
    }

    @Override
    public String takeScreenshotAsBase64PNG(String url) throws IOException {
        try {
            // load the page
            driver.get(url);
            driver.manage().window().maximize();

            // wait for page to render completely
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(15));
            wait.until(webDriver -> "complete".equals(
                    ((JavascriptExecutor) webDriver).executeScript("return document.readyState")
            ));

            // also wait for the body to appear completely.
            WebElement body = wait.until(webDriver -> driver.findElement(By.tagName("body")));

            String previousScreenshot;
            String currentscreenshot = "";
            int max = 20;

            // keep taking shots until all visual elements have stopped moving
            do {
                TimeUnit.SECONDS.sleep(1);
                previousScreenshot = currentscreenshot;
                currentscreenshot = body.getScreenshotAs(OutputType.BASE64);
            } while (previousScreenshot.equals(currentscreenshot) && max-- > 0);

            if (currentscreenshot.isEmpty()) {
                throw new IOException("screenshot is empty");
            }

            return currentscreenshot;
        } catch (InterruptedException e) {
            throw new IOException("screenshot failure", e);
        }
    }
}
