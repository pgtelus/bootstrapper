# About
This is the bootstrapper for the Helios Decompiler. It handles downloading and updating the Helios implementation.

Currently, every push to the implementation repo is considered an update, as Helios is still under development. These updates are served as patch files to minimize network usage.

The bootstrapper tries to account for every potential error to create a positive user experience, but should you encounter any errors which made your experience non-intuitive please open an issue describing the steps to recreate the issue and any system details

# Downloads

The latest bootstrapper can be found [here](https://ci.samczsun.com/job/bootstrapper/lastSuccessfulBuild/artifact/target/bootstrapper-0.0.1-without-swt.jar). Although a download is available with SWT it is not recommended due to the larger filesize.

Make sure you update your bootstrapper often during the alpha stage as changes could be made which fix potential bugs and enhance the user experience.

# License

The bootstrapper, like all projects under the Helios Decompiler name, is licensed under the Apache 2.0 License

# Contributing

Thanks for your interest! Please note that you will need to [sign the CLA](https://www.clahub.com/agreements/helios-decompiler/bootstrapper) 